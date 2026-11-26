# Player settings panel design review (2026-11-26)

Reviewer: design / architecture / playback (Opus). Design: `docs/design/m6-player-settings-panel.md` (proposed
2026-11-26), checked against `main` `f9c4a8a`, the `playback` worktree (`.claude/worktrees/playback`, `d1eb7eb`, not
merged), the score-sheet branch (`dcc914d`), and the Media3 1.11.1 sources in the Gradle cache. Line numbers are on
`main` unless a branch is named.

**Verdict: approve with changes.** The core shape is right: one settings machine with a second presentation, mode stays
FULL, the guide owns the keys behind a flag, and the panel reuses the picker box so the score bug and the reminder card
already avoid it. Most of the facts in §1 check out. The weak part is §4, which is where the owner's goal lives. Several
rows the panel promises to "apply now" either do nothing on the channels the owner is testing or report success without
applying. A few key-path details also differ from full-screen Settings in ways the design does not notice. One contract
gap has to close in commit 0. Everything else is a WP-level fix or a cut.

Counts: Blocker 1 · Should 12 · Nice 11.

---

## Blocker

**B1. On channels with per-channel state, the panel's main rows do nothing, yet the notice says "Applied to this
channel".** (§4.2 steps 6–7, R3, §12 item 2)
- Evidence:
  - The buffer comes from `c.memory.bufferMode ?: s.bufferMode` (`PlaybackControllerImpl.kt:1198`).
  - The decoder comes from `c.decoderOverride ?: c.memory.videoDecoder ?: settings.videoDecoderMode` (`:1243-1246`).
    The quick bar's Decoder chip writes that override, and channel memory keeps it.
  - Tunneling is `s.tunneling && !c.tunnelingOff` (`:1203`). `c.tunnelingOff` comes from saved memory (`:1084`) on
    `main`. On the `playback` branch it also comes from the visit: `carryVisit` (`PlaybackControllerImpl.kt:3770-3799`
    on the branch) copies `tunnelingAutoOff` and `damped` into every non-user restart, and `retry()` is a non-user
    restart (`:737-745`, `startCycle(..., userTune = false)`).
  - `applyFromPanel` returns `APPLIED` whenever `reapplySettings()` ran (§4.2 step 7). The applier cannot tell that the
    global value was shadowed, because `reapplySettings()` returns `Unit`.
- Failure: the owner's test is "change Decoder / Buffer / Tunneling and watch". On a channel where he once used the
  Decoder chip, the ladder learned a buffer mode, or tunneling was switched off, the stream re-prepares (a visible
  rebuffer) and the notice says "Applied to this channel". Nothing changed. That is exactly the "is my edit helping?"
  confusion the panel exists to remove, and the design's own checklist (§12 item 2, "the stats' decoder line changes")
  fails on such a channel.
- Change (commit 0, because it changes contracts):
  1. `PlaybackController.reapplySettings()` (and the restart call in S7) returns an outcome, at least
     `APPLIED | SHADOWED(kind) | NOT_APPLICABLE`. The controller is the only place that knows the effective source.
  2. Add `PanelApplyResult.CHANNEL_OVERRIDE` with the notice "This channel uses its own <decoder|buffer> (quick bar).
     Change it there." The band shows the same line while such a row is focused (R3's follow-up, now required).
  3. An explicit user change to `playback.tunneling` (in the panel or full screen) clears `c.tunnelingOff` learned for
     this visit and the B2 damping for the playing channel. It is a user decision, and the per-visit rule exists so
     the user can try again. This lands in `PlaybackControllerImpl` **after** the `playback` branch merges (see "Merge
     order").

---

## Should

**S1. The panel runs on the PLAYER key table, not Settings', and a held BACK can drop the viewer into the guide.**
(§2.2, R4)
- Evidence: the router picks `KeyContext.PLAYER` whenever the mode is FULL (`KeyRouterImpl.kt:170-171`). In PLAYER,
  BACK has no long binding (`DefaultKeyMap.kt:30-32`: only the guide binds long BACK → PLAY), so a held BACK produces a
  `Press` on every repeat (`KeyGestureDetectorImpl`, "no long binding → Press(repeat)"). `popPage(fromBack = true)` has
  no repeat guard (`SettingsMachineImpl.kt:432, 766-778`). PAGE_UP/PAGE_DOWN map to ZAP_UP/ZAP_DOWN (`:34-35`), which the
  settings tables also accept as page keys, so that difference is harmless. `ui.horizontalRepeat = false` (design §2.2)
  restores the long bindings GROUP_PREV/GROUP_NEXT on LEFT/RIGHT (`DefaultKeyMap.kt:27`, `KeyRouterImpl.kt:173-174`).
- Failure:
  - Holding BACK three pages deep pops every page at ~20 Hz and closes the panel. The rest of the repeats reach the
    player: the quick bar closes, then `backAtBase` runs `returnToGuide()`. The show ends up in the mini player, which
    is the opposite of the owner's ask.
  - LEFT and RIGHT act on key-**up** in the panel (they have a long binding), but on key-down in full-screen Settings.
    RIGHT enters sections and links, so every step into the tree feels laggy next to UP and DOWN.
- Change: add `GuideStateMachine.keyContext` (commit 0). It returns `GUIDE` while `settingsPanel` is set, and the
  router uses it in place of `mode == FULL`. The panel then has exactly the full-screen Settings key table, so the
  `horizontalRepeat` override goes away (leave it as the player computes it). Panel PASS_THROUGH becomes
  `PLAY_PAUSE` (MEDIA_PLAY and MEDIA_PAUSE map to it in GUIDE). Bindable STATS_OVERLAY is player-only
  (`ActionCatalog.kt:38`) and has no default key, so keep a PLAYER lookup only for actions in PASS_THROUGH, or accept
  the loss (see N4). If the lead prefers to keep PLAYER, `panelKey` must at least drop BACK and LEFT with
  `repeat > 0` and swallow the rest of a hold that closed the panel. Test: a held BACK from depth 3 leaves FULL showing
  the quick bar.

**S2. Some paths leave FULL without closing the panel, so the S9 visit can stay open.** (§2.2 "setMode closes an
open panel", §2.3)
- Evidence:
  - Today S9 relies on `leavingSettings()`, which only runs from `setMode` when the mode *is* SETTINGS
    (`GuideStateMachineImpl.kt:953-965`).
  - `commitEntry(EntryFrom.FULL)` writes `u.mode = GUIDE` directly and calls `full.leaveFull()` without `setMode`
    (`:877-883`). `enterGuide` reaches it from `returnToGuide()` (`:830`), including `cancelPin` for a refused tune in
    FULL with nothing playing (`:1279-1281`), and from `fullKey` (`:788, 791`).
  - `leaveFull()` and `closeAll()` reset the player to `NONE` (`PlayerStateMachineImpl.kt:349-357, 567-580`).
  - `syncError` calls `closeAllKeepPassive()` and sets `ERROR` (`:1031-1042`).
- Failure: the guide flag and the player layer drift apart. A visit that never gets `onLeave` never applies its
  REPREPARE rows, keeps page sources observing (`SettingsEffectsImpl.onClosed` is what closes them, `:151-169`), and the
  next `open()` continues the stale visit (`visitOpen` stays true, so `changed` is not cleared, `SettingsMachineImpl.kt:139-144`).
- Change: make the guide flag the only source of truth and enforce it once, in `publish()`. If `settingsPanel` is set
  and the mode is not FULL, or `full` or `settings` is null, run `closePanel()`. At the end of every dispatch, call
  `full.settingsPanel(settingsPanel)` idempotently. The player keeps `SETTINGS_PANEL` across `syncError`, `enterFull`
  and `closeAll` while that call says open. WP-1 test: every path into GUIDE, SEARCH and SETTINGS with the panel open
  calls `onLeave` exactly once.

**S3. Playlist rows are written fire-and-forget, so the automatic restart can read the old playlist row.** (§4.2 "the
store write is in memory by then")
- Evidence: WP-B's playlist page writes through `launch { d.admin.set…(id, …) }` on its own scope (`PlaylistPages.kt:350,
  353, 373, 377, 394-398, 404`; `PageSupport.kt:81`). `onIntent` returns before Room commits. The controller re-reads
  the row on a new cycle (`readFacts` → `DataPlaybackSourceProvider.playlist`, `:73`, no cache).
- Failure: the restart can run against the pre-change row. The panel says "Restarting the channel to apply…" and plays
  the old user agent or format. This is rare at 400 ms, but it is a silent no-op when it happens, and the 400 ms
  debounce does not order the two coroutines.
- Change: the five or six NEXT_TUNE playlist intents the panel acts on await their write (`withContext`/`join` instead of
  `launch`) before `onIntent` returns. That is one-line changes in `PlaylistPages.kt`; put them in WP-4, which already
  owns the file. Alternatively, the applier waits for the playlist row's next emission. The await is simpler.

**S4. `RETUNE_ROWS` match exact ids, but the playlist page's sub-rows carry other ids.** (§4.2)
- Evidence:
  - Custom user agent is `playlist.user_agent.custom` (`PlaylistPages.kt:462`, intent at `:350`).
  - Turning Ignore TLS **on** goes through a confirm whose intent row is `playlist.ignore_tls.confirmed` (`:377, :477`).
  - Removing a header or a backup server is a menu intent on `playlist.headers.h.N` and `playlist.backup_servers.s.N`
    (`:464, :468, :422`).
- Failure: the most likely fixes from an error card (a custom UA, Ignore TLS on, removing a bad header) never trigger
  the restart.
- Change: classify by prefix (`key == id || key.startsWith("$id.")`) and say so in `SettingsPanelRules`. Extend
  `SettingsPanelRulesTest` to the sub-row ids.

**S5. Audio language and subtitles do not need a restart.** (§4.2 `RETUNE_ROWS`, §13 item 2)
- Evidence: the global language preferences are applied only on a cycle's first session (`if (c.firstSession)`,
  `PlaybackControllerImpl.kt:1206-1214`). So `reapplySettings` does not apply them and a new cycle does (that answers
  §13 item 2). Track selection, though, is live: the quick bar's Audio and Subtitles pickers switch tracks through
  `trackSelectionParameters` overrides with no reconnect (`:2352-2380`). Per-channel memory (`c.memory.audioLanguage`,
  `subtitleLanguage`) also wins over the global row, which is the same shadowing as B1.
- Failure: changing the preferred language costs a reconnect and discards the rewind buffer for something the player
  can do in place. On a channel where the user picked a track it changes nothing anyway.
- Change: drop both rows from `RETUNE_ROWS`. Either add a small controller call, `applyTrackPreferences()`, which
  rebuilds the preferred-language parameters on the live player (same mechanism as the pickers) and reports
  `CHANNEL_OVERRIDE` when memory wins, or (v1) leave them as NEXT_TUNE text and point to the quick bar's Audio and
  Subtitles chips, which already do this live.

**S6. The catch-up rows cannot apply through `archive.reapply`, and time-shifted viewing gets "Applied" for nothing.**
(§4.2 steps 4 and 6, `ARCHIVE_RETUNE_ROWS`, R9)
- Evidence: an archive session caches its `Ctx` (`CatchupTarget`, `TimeBase`, frames, variants, days;
  `ArchivePlaybackImpl.kt:154-163`) when it starts. `reapply` re-requests from `s.ctx` (`:434-442` → `seekRerequest` →
  `buildFrom(ctx, …)`, `:1075-1076`). Catch-up mode, zone, padding and days are therefore **not** re-read. For a LOCAL
  session, `reapply` calls `controller.reapplySettings()` (`:438`), which re-prepares the **reader** only. User agent,
  headers, format and TLS live on the writer's connection. Step 4 runs before step 6, so a RETUNE row while
  time-shifted lands there.
- Failure: the five `ARCHIVE_RETUNE_ROWS` never apply. Watching behind live, a stream-format or UA change reports
  APPLIED and changes nothing. Watching at the live edge in a buffered cycle, `retry()` discards the buffer
  (`closeTimeshift`, `PlaybackControllerImpl.kt:754-758`).
- Change: cut `ARCHIVE_RETUNE_ROWS` from v1. Those rows keep "Applies at the next catch-up request". When the
  playhead is behind live (LOCAL or provider archive), RETUNE rows do **not** restart. They answer
  `WAITS_FOR_LIVE` ("Applies when you return to live or change channel"). Step 4 handles REPREPARE rows only.

**S7. The automatic restart needs pacing and must not reset the re-resolve limiter.** (§4.2 step 6, §4.3, R1, Q1)
- Evidence: `retry()` calls `limiter.reset(limiterKey(c))` and starts a fresh ladder (`PlaybackControllerImpl.kt:710-718`).
  That is the card's user-paced "Retry" (§4.3 of the playback design). The 400 ms debounce only merges changes inside
  one window. Two edits two seconds apart give two full cycles. The broker cancels our old call first, but Xtream panels
  commonly hold the slot for seconds after the TCP close.
- Failure: on a one-connection account, editing UA, then format, then headers is three reconnects in a few seconds,
  each with the limiter cleared. That can produce the provider's max-connections refusal (an error card the user caused)
  and defeats the re-resolve window the ladder relies on.
- Change: use a controller entry `restartForSettings()` for the panel instead of `retry()`. It starts a new cycle, keeps
  the limiter, and is a user tune on the `playback` branch (a new visit, so the S1 clearing in B1 applies). The applier
  allows at most one restart per `PANEL_RESTART_MIN_GAP_MS` (suggest 5 s), with a trailing restart for anything
  changed inside the gap. Narrow `RETUNE_ROWS` to the rows that change the *current* connection: `user_agent`,
  `headers`, `stream_format`, `ignore_tls`. `backup_servers` and `max_connections` restart only when the error card
  is up (step 5). `timeshift` and `timeshift_location` restart nothing: they would destroy the buffer and start storage
  preparation for a setting that only matters at the next tune. Keep the NEXT_TUNE text.

**S8. The description band truncates exactly the sentence the owner needs.** (§4.4, §5.6)
- Evidence: builders append the timing sentence **after** the description (`WpaPages.kt:860-863`). At 22 px in a 432 px
  text box, 3 lines hold about 110 characters at Default and about 95 at Largest. Real descriptions run to 188
  (`settings_pb_timeshift_hold_desc`), 155 (`settings_pb_timeshift_desc`, before its arguments), 141 (`live_speed`) and
  117 (`audio_buffer`). Nine descriptions exceed 110 before the timing sentence is added (`strings_settings.xml`).
- Failure: "Applies right away" / "Restarts this channel…" falls past the ellipsis on the rows where it matters most
  (buffer, live speed, audio buffer, timeshift).
- Change: draw the timing as its **own first line** of the band, from `row.effect` (the row already carries it,
  `SettingsContracts.kt` `SettingsRow.effect`) plus the panel's rule (REPREPARE / RETUNE / plain timing text), styled
  apart (accent bar, as the notice). Builders omit the appended sentence when `ctx.panel`. That keeps WP-4 to one
  branch in `WpaPages.row()` and the five PlaylistPages descriptions. Let the band take 4 lines (it is the only place
  descriptions show). The rows area still holds about 6 two-line rows at Default.

**S9. Move mode does not fit: `MoveModeRow` is a fixed 1,276 px wide.** (§2.7 "Move mode … no change", F23)
- Evidence: `MoveModeRow.kt:38` `.width(1276.dp)`. Its hint ("↑↓ … OK … Back") also sits inline at the right (`:45-51`).
  F23 lists the progress track, `VALUE_MAX_W` and the wordmark, but misses this.
- Failure: the lifted row overflows the 464 px column, and the panel's clip cuts the value and the hint (Groups,
  favourite lists and list entries all use it).
- Change: add a `width` (or `fillMaxWidth`) parameter. In the panel the hint moves to the description band while
  `ui.move != null`. Add the case to `SettingsCompactRowTest` and `SettingsPanelBoundsTest`.

**S10. `ExpandSettings` is dispatched re-entrantly from inside `settings.act`.** (§2.1 "after every path change … it
dispatches", R5)
- Evidence: a key reaches `pushRoute` inside `panelKey → settings.act → select → openLink`. The design dispatches
  `GuideEvent.ExpandSettings` from there, and `GuideStateMachineImpl.dispatch` runs it inline because `dispatching`
  is already true (`:347-349`). `expandPanel()` then calls `setMode` → `full.leaveFull()` and
  `settings.present(FULL_SCREEN)` (which reloads the page) while `SettingsMachineImpl.act` is still on the stack and
  has not run its `publish()` (`:189-191`).
- Failure: order-dependent state in a machine that was never written to be re-entered (overlays closed by the push,
  then the reload, then the outer publish). This is the kind of bug that shows only on device.
- Change: on the key path, the guide checks after `settings.act` returns: if the panel is still open and
  `SettingsPanelRules.expands(settings.ui.value.route)`, call `expandPanel()`. Only navigation outside a key
  (`SettingsShellLink.push/replace/popTo/pushGated`, PIN grants) dispatches, and then from a `main.post`, not inline.

**S11. Holding OK on almost any row would expand the panel to full screen.** (§0 item 5, §2.6 `expandKey`, §3)
- Evidence: a long OK is `CONTEXT_MENU` in both contexts (`DefaultKeyMap.kt:28`), the same action as the ☰ key.
  `expandKey` expands on "Menu on a row without a context menu", and only Item rows have menus
  (`SettingsMachineImpl.kt:433-436`).
- Failure: a slightly long OK on Video decoder, Buffer or any toggle turns the panel into opaque full-screen Settings
  and hides the video. Fire TV remotes make long OK an easy accident.
- Change: expand only on the Settings key and on CONTEXT_MENU at the root list. Keep the "☰ Full screen" hint at the
  root only.

**S12. The playback branch and this design both touch the restart path. Merge the branch first.** (§11, R7)
- Evidence: the `playback` branch changes `PlaybackControllerImpl` by about 409 lines. Relevant here: `startCycle(…,
  userTune)`, `carryVisit`, per-visit tunneling, the Auto TS retry on new cycles (`autoTsRetryDue`), and HLS renewal
  counters (branch `:737-745, 3770-3799, 3618+`). B1, S5 and S7 all need small controller changes. The score-sheet
  branch does **not** touch `FullScreenHost.kt` (`git diff --stat main...dcc914d`: PickerSheet, ScoreSheet*,
  HeaderTabs, strings, tests), so R7 and WP-5's wait are moot.
- Change: merge `playback` → `main` first. Then add one small playback WP (Opus) for the panel's controller additions:
  the reapply outcome, `restartForSettings()` as a user tune that keeps the limiter, clearing visit tunneling on a user
  change, and optionally `applyTrackPreferences()`. It conflicts with nothing else, and a panel restart on Auto will
  rightly be eligible for the branch's TS retry. On the branch, check that `reapplySettings` (`reuseResolved`) after an
  in-place HLS renewal reopens the panel URL (which redirects afresh), not a pinned edge URL that has expired.

---

## Nice

**N1. Keep the section preview.** (§2.1) Skipping `requestDisplayed()` at Root in PANEL
(`SettingsMachineImpl.kt:940-943`) saves a background build per cursor move, but every section then opens on a
placeholder frame. The build already runs on `Dispatchers.Default` and closes with the route. Drop the branch: one fewer
presentation-dependent code path.

**N2. Expand more of the storage walk; the panel gains nothing from it.** (§2.6) `FindComputers` (infinite
`SearchingDots`, `SettingsUi.kt` `rememberInfiniteTransition`, contrary to §0 item 11), `ComputerShares`,
`ComputerSignIn`, `LocationSignIn`, `NetworkFolder`, `LocationTest` (writes 16 MB over the same network as the stream,
so the stats would mislead), and `Licences` (long text). None of them has a visible effect on the video. Expanding them
removes the `ProgressTrack` width work from WP-5. `PauseRewind` and `SaveLocation` stay (rows only).

**N3. Fix the `channel.*` contradiction.** `RETUNE_ROWS` lists `channel.decoder/tunneling/afr`, but §2.6 says
`Channel(identity)` cannot be reached from the panel. Drop them from v1 rather than add an entry point.

**N4. Stream info cannot be switched on from inside the panel with the default remote.** There is no default
STATS_OVERLAY key (`DefaultKeyMap.kt`, `ActionCatalog.kt:38` player-only), and the quick bar's Stream info chip does not
take keys while the panel is open. Put "Stream info, then Settings" in §5.10 and the checklist. A follow-up could add a
panel-only "Stream info" toggle above the root list.

**N5. Losses on expand.** `expandPanel()` → `setMode(SETTINGS)` → `leaveFull()` turns the stats off
(`PlayerStateMachineImpl.kt:353`). BACK then lands in FULL via `goFull(USER)` with the passive info bar, not the quick
bar or the panel. `present(FULL_SCREEN)` reloads only the displayed page, so cached parent pages keep panel texts until
rebuilt. Clear the page cache on `present()` instead (cheap), and restore stats if they were on before the expand.

**N6. Detached route, LEFT at the bottom.** §2.4 says LEFT behaves like BACK there. `popPage(fromBack = false)` returns
`true` (consumed, nothing happens) at a detached bottom (`SettingsMachineImpl.kt:776`). Fix the table, or the code if
"LEFT closes" is wanted.

**N7. Option text width.** The inline option list at indent 24 leaves about 376 px for the label: about 25 characters
at Largest (29 px). Catch-up zone names (`America/Argentina/Buenos_Aires`) and UA presets will ellipsise. Give option
labels `maxLines = 2` in compact mode, and give the second-line `detail` 1 line.

**N8. Account line at Largest.** The mock-up's second line "Xtream · 13,110 channels · updated 00:25" is about 540 px at
26 px. Let Info rows in compact mode wrap to 2 lines before ellipsising (they already allow 3 lines in §2.7, so state
that the account card uses it).

**N9. Re-entrant `full.settingsPanel(true)` from `quickSelect`.** `quickSelect(SETTINGS)` → `effects.openSettings()`
→ `GuideEvent.OpenSettings` runs synchronously inside the player's own `act` (`PlayerStateMachineImpl.kt:838`,
`PlayerEffectsImpl.kt:169-171`). It is the same pattern as today's `leaveFull`, but add a WP-2 test that the layer after
`act` returns is `SETTINGS_PANEL` and no quick-bar timer is armed.

**N10. TalkBack on open.** The machine does not announce on `open()`. Announce "Settings, <page title>" when the panel
opens and on expand, so VoiceView users know focus moved off the quick bar.

**N11. Seek keys.** FF, RW and skip keys are swallowed by the panel. Passing SEEK_BACK, SEEK_FORWARD and JUMP_TO_LIVE
through while time-shifted would let the owner rewind a few seconds to re-check a hitch, without closing the panel.
Optional.

---

## The three facts §13 could not establish

1. **Does `retry()` re-resolve with the new playlist settings?** Yes. It starts a new `Cycle`: `factsLoaded = false`,
   and `resolved`, `userAgentOverride`, `output` and `baseIndex` are all cleared. The first session reads
   `provider.playlist(id)` straight from Room (`DataPlaybackSourceProvider.kt:73`, no cache) and resolves with
   `reuse = null` (`PlaybackControllerImpl.kt:896-925`). The resolver keeps no cache ("No cache of resolved URLs",
   `StreamUrlResolverImpl.kt:35`). Caveats:
   - the write race (S3);
   - `xtreamOutputLearned` still wins over **Auto** (`StreamUrlBuilder.kt:183, 201`), so choosing Auto on a playlist
     that learned HLS keeps HLS;
   - on the `playback` branch, `retry()` is not a user tune, so visit state carries over (B1).
2. **Do audio language and subtitle preferences apply on a re-prepare?** No. They apply once per cycle
   (`firstSession`, `:1206-1214`), and per-channel memory comes first. A new cycle applies them. Track parameters can
   do it live without either (S5).
3. **Does a re-prepare re-create the AudioTrack?** Yes, on every device, because the library code does it
   unconditionally:
   - A re-prepare disables the renderers (through `stop()`, or `setMediaSource(resetPosition)` on the reader-only path).
   - `MediaCodecAudioRenderer.onDisabled` calls `audioSink.flush()` (Media3 1.11.1, `MediaCodecAudioRenderer.java:792-806`).
     The FFmpeg `DecoderAudioRenderer.onDisabled` calls `audioSink.reset()` (`DecoderAudioRenderer.java:751-761`).
   - `DefaultAudioSink.flush()` always releases the audio output ("We need to release the audio output on every
     flush", `DefaultAudioSink.java:1596-1600`).
   - The next format runs `configure()` → `audioOutputProvider.getOutputConfig()` (`:808`), which asks the buffer-size
     provider (`AudioTrackAudioOutputProvider.java:245, 317`).
   So WP-6's "Audio buffer" provider that reads the current value would apply on any re-prepare. Spike S1 shrinks to a
   unit test. The device's minimum AudioTrack buffer still varies, but that only affects the size, not whether the
   track is re-created.

---

## Open questions (§9)

- **Q1 (restart by itself): agree, with conditions.** Restart only for rows that change the current connection (S7).
  Never while watching behind live (S6). At most one restart per 5 s. Never for audio language or subtitles (S5). The
  notice already says it is restarting.
- **Q2 (Settings key opens the panel): agree.** A second press expands, which gives a natural small → full toggle.
- **Q3 (error card → panel beside the card): agree.** It is the strongest case for the feature: change it, the card
  retries (step 5), BACK returns to the card. Use the S7 pacing here too.
- **Q4 (CH± pages the list): agree for v1.** Zapping from the panel would split per-playlist applies across channels
  and the `OTHER_PLAYLIST` rule. BACK, then zap, is two keys.
- **Q5 (reopen at root): mild disagreement.** The owner's loop is "change, watch, change again", and reopening at the
  root costs two or three keys each time. The machine keeps per-route cursor memory across visits (`memory` is not
  cleared in `onLeave`, `SettingsMachineImpl.kt:194-204`) and `open(route)` builds the path (`pathTo`). Reopen at the
  last page when it is reopened within about 10 minutes on the same playlist, otherwise at the root. This is small
  enough for v1, but it can follow if time is short.

---

## Cut or defer (first version)

The owner's goal is "edit and see it on the playing video". This first version meets it:

- **Keep:**
  - panel presentation and compact rows;
  - guide-flag ownership with S1 and S2;
  - REPREPARE applied at once (decoder, buffer, audio output, tunneling, live speed), with B1's honest result;
  - the error-card detached panel with auto-retry;
  - restart for UA, headers, stream format and TLS on the playing playlist (S3, S4, S7);
  - the active Settings pill;
  - the description band with the timing line (S8);
  - the PIN path (free).
- **Cut or defer:**
  - WP-6 (already optional);
  - `ARCHIVE_RETUNE_ROWS` (S6);
  - the `channel.*` retune rows (N3);
  - the timeshift, timeshift location, backup servers and max connections restarts (S7; backup servers and max
    connections still retry on the error card);
  - the audio language and subtitles retune (S5);
  - the section-preview skip (N1);
  - the storage-walk routes in the panel (N2), which also cuts the `ProgressTrack` width change;
  - "expand on ☰ for rows without a menu" (S11).
- Together these remove most of `SettingsPanelRules`' row sets and the archive branch of `applyFromPanel`.

## Work packages and file ownership

The split is close to disjoint, with these corrections:
- **Commit 0:** add the reapply outcome and `PanelApplyResult.CHANNEL_OVERRIDE` / `WAITS_FOR_LIVE` (B1, S6),
  `GuideStateMachine.keyContext` (S1), and their fakes (`FakeGuideStateMachine` in `:ui` tests, `FakeSettings`).
- **`KeyRouterImpl.kt`** (S1) is owned by no WP. Give it to WP-1.
- **`PlaylistPages.kt`** is in WP-4 (texts). The S3 awaits go there too, which is fine but must be in its brief.
- **New playback WP (Opus), after the `playback` branch merges:** `PlaybackControllerImpl.kt` and `PlaybackApi.kt`
  (B1, S5 optional, S7). WP-3's applier depends on its contract only (commit 0), so WP-3 can run in parallel against
  a fake.
- **WP-5 does not need to wait** for the score-sheet branch (S12). It only reads `PlayerUi.kt` constants.

## Merge order

1. Score-sheet branch (independent).
2. `playback` branch.
3. Commit 0.
4. WP-1 to WP-5 and the new playback WP in parallel.
5. Integration.

## Not reviewed

Visual polish of the active pill against the owner's mock-up; IME behaviour on Fire OS over FULL (the editor overlay is
unchanged, but a device check belongs in the §12 checklist); the exact rebuffer cost of a decoder re-prepare on a Fire
TV Stick (measure in §12 item 2).
