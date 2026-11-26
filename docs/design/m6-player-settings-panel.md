# Player settings panel: design

Status: **revised** 2026-11-26 after the review `docs/review/2026-11-26-player-settings-panel-design-review.md` and the
lead's rulings (B1 and S1–S12 accepted; first version = the review's "Keep" list; WP split around the unmerged
`playback` branch). Proposer, Opus. §14 lists each finding and where it landed.

Owner request (verbatim): "also when selecting the settings from the in player settings chip i would like a smaller
setting in the player so we can see if edit are helping without going back and forth and we can keep the show we are
wathing viable"

Owner mock-up (described): the video plays full screen. A tall, narrow, translucent rounded panel sits at the right edge,
about a quarter of the width, from near the top to just above the quick bar. The quick bar stays visible with its chips
(Add to favourites, Start over, Previous, Stream info, Decoder · Auto, Screen off, Score bug, Settings) and the
**Settings chip highlighted**. Panel content: a header "← App Settings | Playlists" (back arrow, root name dim, divider,
current page bright), a hairline, a section title "Playlists", then a focused card "Jarvis (Active)" with a second
line "Xtream · 13,110 channels · updated 00:25", then "Add playlist" (+), "Set up from phone" (phone icon), "Refresh
all now" (refresh icon). One column. The mock-up's blur is replaced by a translucent fill (CLAUDE.md: no real-time blur).

Builds on `docs/design/m5-setup-settings.md` ("M5 §n"), `docs/design/m4-player-ux.md` ("M4 §n"),
`docs/design/m6-score-bug.md` ("SB §n"), `main` `9fb14ad` (score-sheet restyle merged), and the `playback` branch
(`.claude/worktrees/playback`, `d1eb7eb`, **not merged**).

**Scope:** a second **presentation** of the existing Settings (machine, tree, page sources, row views) over FULL. No
second settings machine, no second tree, no new page builders.

**Two stages.**
- **Stage A** builds on `main` now. It touches no file the `playback` branch changes except three shared contract and
  machine files, where the edits are additive and in other regions (§11.3). It ships the panel, the key routing, the
  expand rule, the layout and the invariants, and applies what already has a working apply path: LIVE rows at once, and
  REPREPARE rows through today's `LiveSettingsApplier.apply()`, reported **without** claiming an effect.
- **Stage B** lands after `playback` merges. It adds the controller's reapply outcome (honest "Applied" and
  "This channel uses its own …"), the paced `restartForSettings()` for the connection rows, and the per-visit tunneling
  reset.

---

## 0. Decisions at a glance

1. **One machine, two presentations.** `SettingsMachineImpl` gets `SettingsPresentation.FULL_SCREEN | PANEL`. A new
   composable, `SettingsPanel`, draws the same `SettingsUiState` in one column: `Pane.SECTIONS` at Root is the root
   list, `Pane.PAGE` is the page (§2.1).
2. **Mode stays FULL; the guide flag is the one source of truth.** `GuideUiState.settingsPanel`. While it is set the
   guide sends every key to `SettingsMachine.act` before the player machine. `publish()` closes the panel whenever the
   mode is not FULL or a machine is missing, and tells the player machine the flag on every dispatch (§2.3, S2).
3. **The panel uses the Settings key table.** New `GuideStateMachine.keyContext`: GUIDE while the panel is open, so a
   held BACK and the D-pad behave exactly as in full-screen Settings. PASS_THROUGH is PLAY_PAUSE only (§2.2, S1).
4. **Player side:** a passive `PlayerLayer.SETTINGS_PANEL` keeps the quick bar up (no auto-hide) with the Settings pill
   drawn active, suppresses the info bar and the dim, and keeps an error card unfocused. It survives `syncError`,
   `enterFull` and `closeAll` while the guide says open (§2.2).
5. **BACK ladder:** overlay / option list → page → parent → … → root list → panel closes, quick bar focused on Settings
   with its 8 s timer. A panel opened from an error card closes back to the card (§2.4).
6. **PIN gate unchanged** (§2.5).
7. **Expand rule:** onboarding, phone, restore, channel editor and the storage walk open as full-screen Settings at the
   same path. Expanding is checked by the guide **after** `settings.act` returns, and by `AppRoot` (posted) for
   navigation outside a key; never re-entrantly (§2.6, S10). Keys that expand: the Settings key anywhere, Menu (☰) on
   the root list only (S11).
8. **Entry points:** quick bar Settings, the Settings key in FULL, and error-card Settings routes (detached) open the
   panel. Everything from the guide stays full screen (§3).
9. **Reopen:** a panel closed less than 10 minutes ago reopens at its last page; otherwise at the root list (Q5, §2.3).
10. **Live effect, stage A:** LIVE rows are visible at once. REPREPARE rows (buffer, audio output, video decoder,
    tunneling, keep up with live) re-prepare the stream 400 ms after the change through today's applier; the notice says
    "Re-preparing the stream" and that a choice made for this channel still wins, never "Applied". Connection rows (user
    agent, headers, stream format, Ignore TLS) say "Applies when you change channel". On the error card nothing is
    re-prepared; the notice says "Close Settings and choose Retry" (§4.2).
11. **Live effect, stage B:** `reapplySettings()` returns an outcome. The panel says "Applied" only when it applied, and
    "This channel uses its own decoder / buffer (quick bar). Change it there." when it was shadowed (B1). The four
    connection rows restart the channel through `restartForSettings()` (keeps the re-resolve limiter, a user tune), at
    most once per 5 s with a trailing restart, never while behind live, and on the error card also for backup servers
    and max connections (S6, S7). A user change to tunneling clears the per-visit tunneling-off (B1) (§4.3).
12. **Layout:** the picker box, (1376, 48) 496 × 880 design px, canvas .86 fill, radius 24. Compact rows with the value
    under the label. A 4-line **description band** at the bottom whose first line is the **timing line**, drawn from
    `row.effect` and the panel rule (S8); notices use the same band (§5).
13. **Coexistence:** quick bar up, info bar off, stats kept (left), score bug avoids the picker box, reminder card
    beside the panel, error card centred and unfocused (§5.8–§5.12).
14. **Rules:** no blur, the panel is a key-sink sibling of `VideoLayer`, no infinite animation in the panel (the
    searching dots' page expands), fades snapped in low-end mode (§6).
15. **Deferred:** decoder hand-off / audio buffer live (old WP-6), catch-up row re-requests, `channel.*` restarts, the
    timeshift restarts, the audio language / subtitles restart, the section-preview skip, and more (§7).

---

## 1. Facts

From `main` unless a branch is named. F24–F31 come from the review.

| # | Fact | Where |
|---|---|---|
| F1 | Quick bar Settings: `quickSelect` → `closeLayer(); effects.openSettings()` → `GuideEvent.OpenSettings` → `openSettingsGated(Root)` | `PlayerStateMachineImpl.kt:838`, `PlayerEffectsImpl.kt:168`, `GuideStateMachineImpl.kt:416` |
| F2 | `openSettings` sets mode SETTINGS; leaving FULL calls `effects.setFullScreen(false)` and `full.leaveFull()`, which closes every player layer and **turns the stats overlay off** | `GuideStateMachineImpl.kt:957–1005`, `PlayerStateMachineImpl.kt:349` |
| F3 | Mode SETTINGS is `HoleMode.COVERED`: the video is hidden; `SettingsScreen` is opaque | `AppRoot.kt:95–130, 458` |
| F4 | The settings machine is UI-agnostic (`pane`, `sections`, `page`, `cursor`, `top`, overlays) | `SettingsMachine.kt`, `SettingsMachineImpl.kt` |
| F5 | SECTIONS: UP/DOWN move, RIGHT/SELECT enter, BACK returns false. PAGE: LEFT and BACK pop; BACK at a detached bottom returns false; **LEFT at a detached bottom is consumed and does nothing** | `SettingsMachineImpl.kt:365–445, 766–778` |
| F6 | `SettingsMachine.act` returns false for OPEN_GUIDE and EXIT | `SettingsMachineImpl.kt:169` |
| F7 | REPREPARE changes apply once, when Settings closes (`onLeave` → `onClosed` → `LiveSettingsApplier.apply()`) | `SettingsMachineImpl.kt:194`, `SettingsEffectsImpl.kt:151` |
| F8 | `LiveSettingsApplierImpl.apply()`: archive session → `archive.reapply(playhead)`; LOCAL paused → reader re-prepare, stays paused; else `controller.reapplySettings()` | `app/.../LiveSettingsApplierImpl.kt` |
| F9 | `reapplySettings()` returns `Unit`; no-op when idle, stopped, VOD/archive, paused unbuffered, or while the error card shows | `PlaybackControllerImpl.kt:646–663` |
| F10 | `retry()` resets the re-resolve limiter and starts a new cycle; it re-resolves from Room with no cache. On the `playback` branch it is a non-user restart (`carryVisit`) | `PlaybackControllerImpl.kt:710`; branch `:737–745, 3770–3799`; `DataPlaybackSourceProvider.kt:73` |
| F11 | AFR is collected live; buffer, decoder, tunneling, audio output are read on each prepare; live speed when the source is built | `PlaybackControllerImpl.kt:984, 1196–1243, 1315` |
| F12 | `codecQueueing` ("Decoder hand-off") and `audioBuffer` are fixed when the one ExoPlayer is built (NEXT_LAUNCH) | `PlayerFactory.kt:67` |
| F13 | Per-channel state wins over the global rows: buffer `c.memory.bufferMode ?: s.bufferMode`; decoder `c.decoderOverride ?: c.memory.videoDecoder ?: global`; tunneling `s.tunneling && !c.tunnelingOff` (on the branch also per visit) | `PlaybackControllerImpl.kt:1084, 1198, 1203, 1243–1246` |
| F14 | Builders append the timing sentence after the description (`WpaPages.row`); WP-B's playlist rows use `SetupText.EFFECT_NEXT_TUNE` as the whole description | `WpaPages.kt:844–865`, `PlaylistPages.kt:236–275` |
| F15 | Text size is fixed per process (RESTART) | `SettingsStore.kt:115–125` |
| F16 | The picker box (1376, 48) 496 × 880 ends 24 above the quick bar; the score bug avoids it (`PlacementInput.picker`) | `PlayerUi.kt:87–92, 152`, `FullScreenHost.kt:272` |
| F17 | Error-card routes: `Playlist(id[, focus])`, `PlaylistSource(id)`, `NetworkTest(id)`, detached, PIN-free; OPEN_EXTERNAL fallback `Section(PLAYBACK)` | `ErrorCardModel.kt:81–94`, `ErrorRouteActionsImpl.kt:74, 104` |
| F18 | No Settings link from the score sheet (also after the restyle merge) | grep |
| F19 | The PIN pad, toasts and the reminder card are drawn by `AppRoot` above every mode | `AppRoot.kt:465–473` |
| F20 | The router hands keys to Compose while `textInputActive` (today: mode SETTINGS only); `AppRoot` re-focuses the sink when the editor closes, in any mode | `KeyRouterImpl.kt:161`, `AppRoot.kt:382–394` |
| F21 | Centred card overlays: `ConfirmCard` 720, `NumberPad` 560, `TextEditorOverlay` 1100 at y 120, `CaptureOverlay` 720 on a .55 scrim; `SettingsMenu` 420 | `SettingsUi.kt:97–100` |
| F22 | `PhoneSetup` and `ChannelEditor` are drawn by other screens over the whole canvas | `SettingsScreen.kt:92, 150` |
| F23 | Fixed widths wider than the panel: `ProgressTrack` 1228, `VALUE_MAX_W` 560, the About wordmark, **`MoveModeRow` 1276** with its hint inline | `SettingsUi.kt:143`, `SettingsRowViews.kt:229`, `MoveModeRow.kt:38–51` |
| F24 | The router picks `KeyContext.PLAYER` whenever the mode is FULL. In PLAYER, BACK has no long binding, so a held BACK repeats as presses; LEFT/RIGHT have long bindings (GROUP_PREV/NEXT) and act on key-up | `KeyRouterImpl.kt:170–174`, `DefaultKeyMap.kt:27–32` |
| F25 | `commitEntry(FULL)` writes `u.mode = GUIDE` without `setMode` (reached from `returnToGuide`, `cancelPin`, `fullKey`); `syncError` and `closeAll` reset the player's layer | `GuideStateMachineImpl.kt:788–791, 830, 877–883, 1279`; `PlayerStateMachineImpl.kt:567, 1031` |
| F26 | WP-B's playlist rows write with `launch { d.admin.set…() }`; `onIntent` returns before Room commits | `PlaylistPages.kt:350–404`, `PageSupport.kt:81` |
| F27 | Playlist sub-rows carry their own ids: `playlist.user_agent.custom`, `playlist.ignore_tls.confirmed`, `playlist.headers.h.N`, `playlist.backup_servers.s.N` | `PlaylistPages.kt:350, 377, 422, 462–477` |
| F28 | Global audio and subtitle language preferences apply on a cycle's first session only; per-channel memory wins; the quick bar's Audio/Subtitles pickers switch tracks live | `PlaybackControllerImpl.kt:1206–1214, 2352–2380` |
| F29 | An archive session re-requests from its cached `Ctx` (mode, zone, padding, days are not re-read); a LOCAL `reapply` re-prepares the reader only (the writer keeps its connection) | `ArchivePlaybackImpl.kt:154–163, 434–442, 1075` |
| F30 | A long OK is `CONTEXT_MENU`, the same action as ☰ | `DefaultKeyMap.kt:28` |
| F31 | A re-prepare re-creates the AudioTrack on every device (renderer disable → sink flush → release) | Media3 1.11.1 (review "three facts" 3) |

---

## 2. Architecture

### 2.1 A presentation, not a fork

Contracts (stage A commit 0, additive, defaulted):

```kotlin
// SettingsMachine.kt
enum class SettingsPresentation { FULL_SCREEN, PANEL }
data class SettingsUiState(/* … */ val presentation: SettingsPresentation = SettingsPresentation.FULL_SCREEN)
interface SettingsMachine {
    /** Switches the presentation; path, cursor, overlays and the S9 visit are untouched. */
    fun present(p: SettingsPresentation) = Unit
}
// SettingsPages.kt
data class PageContext(val advanced: Boolean = true, val nowMs: Long, val panel: Boolean = false)
```

`SettingsMachineImpl`:
- publishes `presentation`;
- `present(p)` during an open visit **clears the page cache** (`pages`, `listRows`, `requested`) and reloads the
  displayed route, so every page on the path is rebuilt with the new presentation's texts when shown (N5);
- keeps the section preview at Root in both presentations (N1: no presentation-dependent branch);
- dispatches **nothing** to the guide on navigation (S10; expanding is decided outside, §2.6).

`SettingsEffectsImpl.ctx()` sets `panel = machine.ui.value.presentation == PANEL`.

### 2.2 Who owns the keys

Contracts (stage A commit 0):
```kotlin
// GuideStateContracts.kt
data class GuideUiState(/* … */ val settingsPanel: Boolean = false)
sealed interface GuideEvent { /* … */ data object ExpandSettings : GuideEvent }
interface GuideStateMachine {
    /** (S1) The key table the router uses: GUIDE while the settings panel is open, else PLAYER in FULL, else GUIDE. */
    val keyContext: KeyContext get() = if (ui.value.mode == ScreenMode.FULL) KeyContext.PLAYER else KeyContext.GUIDE
}
// PlayerContracts.kt
enum class PlayerLayer { /* … */ PLACE_SCORE, SETTINGS_PANEL } // append-only
data class QuickBarState(/* … */ val panelOpen: Boolean = false)
interface PlayerStateMachine { fun settingsPanel(open: Boolean) = Unit }
```

**Router (S1).** `KeyRouterImpl` uses `m.keyContext` instead of `mode == FULL`. With the panel open the keys are read
from the GUIDE table, the same as full-screen Settings: a held BACK has its long binding (PLAY) and does not repeat-pop,
LEFT/RIGHT act on key-down. `GuideStateMachineImpl.horizontalRepeat` keeps today's formula (no panel override).

**Guide.** `GuideStateMachineImpl.act`, order:
1. `dropEntry`, the PIN prompt, the reminder card (modal in FULL), as today.
2. **`if (u.mode == FULL && settingsPanel) { panelKey(a); return }`**, before `overlayKey`, the player and `globalKey`.

`panelKey(a)`:
- `a.action == PLAY_PAUSE` (PASS_THROUGH; MEDIA_PLAY and MEDIA_PAUSE map to it in GUIDE) → `full.act(a)`.
- `SettingsPanelRules.expandKey(settings.ui.value, a.action)` → `expandPanel()` (§2.6).
- otherwise `consumed = settings.act(a)`. Then, if the panel is still open and
  `SettingsPanelRules.expands(settings.ui.value.route)` → `expandPanel()` (S10). When `consumed` is false: BACK →
  `closePanel()`; OPEN_GUIDE → `closePanel()` then `returnToGuide()`; EXIT → `closePanel()` then `exitStep()`.
- A long BACK arrives as PLAY (GUIDE table): not PASS_THROUGH, so the settings machine consumes it as a no-op.

`textInputActive` becomes `(mode == SETTINGS || settingsPanel) && settings?.textInputActive == true`.

The Stats key is a PLAYER-table action with no default key, so it is not passed through (N4): turn **Stream info** on
from the quick bar before choosing Settings (§5.10).

**Player.** In `PlayerLayer.SETTINGS_PANEL` the player machine:
- publishes `quick = quickState().copy(focused = index of SETTINGS, panelOpen = true)`, arms no `quickHideAt`;
- shows no passive info bar; the quick bar's values refresh on a channel change (`FavPurpose.QUICK` answers accepted);
- never dims a paused frame (`dimmed()` false);
- keeps the stats overlay as it was;
- `act` handles PLAY_PAUSE (as on bare video) and returns false for anything else;
- **keeps SETTINGS_PANEL** across `syncError` (the card is published with `focused = -1`), `enterFull` and `closeAll`
  while the last `settingsPanel(true)` stands (S2).

`settingsPanel(true)` remembers the layer it came from (QUICK_BAR or ERROR) and closes the picker, list, scrub and place
mode. `settingsPanel(false)` returns to QUICK_BAR with `quickFocus = SETTINGS` and an 8 s `quickHideAt`, or to ERROR
while the error is active. Both are idempotent.

`quickSelect(SETTINGS)` no longer calls `closeLayer()`: the quick bar stays under the PIN prompt and the panel. A
synchronous `settingsPanel(true)` from inside the player's own `act` (via `effects.openSettings()`) is expected; the
layer after `act` returns is SETTINGS_PANEL with no timer (N9 test).

### 2.3 Open, close, reopen, and the one source of truth

```kotlin
// GuideStateMachineImpl
private fun openSettings(route: SettingsRoute = Root, detached: Boolean = false) {
    if (u.mode == ScreenMode.FULL && settings != null && full != null && !SettingsPanelRules.expands(route)) {
        openPanel(reopenRoute(route, detached), detached); return
    }
    /* existing body */
}
private fun openPanel(route, detached) {
    endBoot(); u = u.copy(overlay = null, settingsPanel = true); settingsPanel = true; panelDetached = detached
    settings!!.present(PANEL)                       // before open: the first page is built with ctx.panel
    settings!!.open(route, fromFull = true, detached = detached)
    // the player hears the flag at the end of this dispatch (publish)
}
private fun closePanel() {
    if (!settingsPanel) return
    settingsPanel = false; u = u.copy(settingsPanel = false)
    if (!panelDetached) { lastPanelRoute = settings?.ui?.value?.route; panelClosedAtMs = curMs }
    settings?.onLeave()                             // S9: once per visit
    settings?.present(FULL_SCREEN)
}
private fun expandPanel() {
    if (!settingsPanel) return
    settingsPanel = false; u = u.copy(settingsPanel = false)
    settingsRoute = settings!!.ui.value.route; settingsDetached = panelDetached
    setMode(ScreenMode.SETTINGS)                    // leaves FULL as today (F2); no onLeave: the visit continues
    settingsFromFull = true
    settings!!.present(FULL_SCREEN)                 // same path and cursor; page cache cleared and rebuilt
}
```

**One source of truth (S2).** At the end of `publish()`:
- if `settingsPanel` is set and (`u.mode != FULL` or `full == null` or `settings == null`) → `closePanel()`. This
  covers `commitEntry(FULL)` (F25), `cancelPin`, `fullKey` and any future path that changes `u.mode` directly;
- then `full?.settingsPanel(settingsPanel)`, every dispatch (idempotent).

`expandPanel()` clears the flag before `setMode`, so the check does not run `onLeave` for it.

**Reopen (Q5, lead ruling).** `reopenRoute(route, detached)`: for a non-detached Root request, if
`curMs − panelClosedAtMs ≤ PANEL_REOPEN_MS` (600 000) and `lastPanelRoute` is set, open there (`open` builds the
parents with `pathTo`; cursor memory survives `onLeave`). Otherwise Root. Detached visits are never remembered.

Other entries:
- the PIN grant (`EnterSettings`) runs `openSettings(route)` while the mode is FULL → the panel;
- `OpenSettingsDetached(route)` → panel, detached (Q3);
- no settings machine attached (S11 of M5) → today's full-screen path;
- after an expand, BACK at Root returns to **FULL** (`settingsFromFull`), with the passive info bar, not the panel.
  The stats overlay is off after an expand (F2); restoring it is deferred (§7).

TalkBack (N10): `openPanel` and `expandPanel` post `SettingsShellEvent.Announce("Settings, <page title>")` through the
settings link once the page is known (the machine announces on its next `announceRow`; the guide asks
`settings.announceOpen()`, a defaulted `SettingsShellLink` member added in commit 0).

### 2.4 BACK at each depth

| Where the focus is | BACK | LEFT |
|---|---|---|
| Text editor | M5 S3 ladder (chips → field → Cancel) | Compose |
| Key capture, number pad, confirm card, context menu, move mode | closes it (M5 §3.2.4) | as today |
| Inline option list | closes it (MultiChoice commits) | same |
| A page below a section page | parent page, cursor restored | same |
| A section page | root list, cursor on that section | same |
| Root list (`Pane.SECTIONS`) | **panel closes**; quick bar focused on Settings, 8 s timer | nothing |
| Detached route, bottom page | **panel closes**; error card focused, or quick bar | nothing (F5, N6) |

A held BACK repeats nothing (GUIDE table, S1): from depth 3, BACK, BACK, BACK closes the panel and the video stays in
FULL with the quick bar. The Guide key closes the panel and enters the guide; Exit closes it and runs the exit step.

### 2.5 PIN gate

Unchanged. "Lock settings" on, PIN set, session locked: Settings chip → PIN pad over the video (F19) → the panel. Gated
rows, links, the Parental section and `needsPin` menu options ask through the same `PinGate`, over the video. Detached
error-card routes stay PIN-free for the route and ask for links into gated routes (M5 S2). The score bug hides under the
PIN pad as today.

### 2.6 Expand rule and the route table

New `core/src/main/kotlin/app/thebox/core/settings/state/SettingsPanelRules.kt` (stage A):

```kotlin
object SettingsPanelRules {
    /** Routes the panel hands to full-screen Settings. Exhaustive `when` (no else): a new route must be classified. */
    fun expands(route: SettingsRoute): Boolean
    /** (S11) The Settings key anywhere; CONTEXT_MENU only on the root list (Pane.SECTIONS at Root). */
    fun expandKey(ui: SettingsUiState, action: BoxAction): Boolean
    /** (S8) The band's timing line for [row] in the panel (§4.4). */
    fun timing(row: SettingsRow): PanelTiming
    const val APPLY_DEBOUNCE_MS = 400L
    const val PANEL_REOPEN_MS = 600_000L
}
enum class PanelTiming { NONE, AT_ONCE, REPREPARE, CHANGE_CHANNEL, NEXT_START, NEXT_UPDATE, NEXT_GUIDE_UPDATE, RESTART_APP }
```

**When expanding is checked (S10).**
- On a key: by the guide after `settings.act` returns (§2.2). Never from inside `settings.act`.
- Outside a key (a page source's `push/replace/popTo/pushGated`, a PIN grant's step): `AppRoot` collects
  `settings.ui.map { it.presentation == PANEL && SettingsPanelRules.expands(it.route) }.distinctUntilChanged()` in a
  `LaunchedEffect` and, on true, dispatches `GuideEvent.ExpandSettings` from that coroutine (a later main-thread turn).
- `ExpandSettings` is idempotent: the guide runs `expandPanel()` only if the panel is open and the route expands.

**Rule:** a route expands when (a) another screen draws it over the whole canvas, (b) it is part of the onboarding or
import flow, (c) it replaces the library, or (d) it is part of the network-folder walk, which has no visible effect on
the video and has an infinite animation or a long transfer (N2).

| Route | Panel / Expand | Why |
|---|---|---|
| `Root` | Panel (root list = `Pane.SECTIONS`) | – |
| `FirstRun` | Expand (never reached from FULL) | Welcome cards |
| `Section(*)` (all eleven) | Panel | rows only |
| `AddSource` | **Expand** | (b) |
| `PhoneSetup(any purpose)` | **Expand** | (a) QR page |
| `ImportProgress`, `GroupsReview`, `MatchingReport` | **Expand** | (b) |
| `Playlist(id[, focus])` | Panel | account card + rows; error-card target |
| `PlaylistSource(id)` | Panel | Text rows; the editor is centred; a save that pushes `ImportProgress` expands then |
| `NetworkTest(id)` | Panel | Info lines and actions |
| `Groups`, `HiddenChannels`, `HideRules` | Panel | Item lists; move mode with S9's width fix |
| `FavouriteLists`, `FavouriteList` | Panel | – |
| `EpgSources`, `SyncStatus`, `FixGuide(null / channel)` | Panel | lists |
| `Channel(identity)` | Panel | not reachable from the panel (guide menu only) |
| `ChannelEditor` | **Expand** | (a) |
| `ButtonMapping`, `ActionBindings` | Panel | the capture card is centred |
| `Diagnostics` | Panel | Info rows |
| `Licences` | **Expand** | long text, nothing to watch for (N2) |
| `Backup` | Panel | its phone links expand when chosen |
| `RestorePreview(token)` | **Expand** | (c) |
| `PauseRewind`, `SaveLocation`, `LocationDetail` | Panel | rows |
| `FindComputers`, `ComputerShares`, `ComputerSignIn`, `LocationSignIn`, `NetworkFolder`, `LocationTest` | **Expand** | (d): searching dots, sign-in forms, a 16 MB test on the stream's network (N2) |

### 2.7 Overlays and row kinds in the panel

| Thing | Treatment |
|---|---|
| `RowKind.Toggle` | compact row, pill at the right |
| `RowKind.Choice` | value under the label; OK opens the inline option list (indent 24, §5.5) |
| `RowKind.MultiChoice` | as Choice, check boxes; value = selected labels joined, ellipsised |
| `RowKind.Text` | value under the label (masked as dots); OK opens `TextEditorOverlay` centred over the video (it overlaps the panel while open; modal). The IME covers the lower half |
| `RowKind.Number` | value + unit under the label; OK or a digit opens `NumberPad` centred |
| `RowKind.Action` | label, destructive dot; `confirm` → `ConfirmCard` centred |
| `RowKind.Link` | label, value when set, chevron; opens in the panel or expands (§2.6) |
| `RowKind.Info` | label + up to 3 lines under it; the account card's line wraps to 2 lines before ellipsising (N8); storage rows: all lines |
| `RowKind.Item` | label, value under it, badges on the value line, chevron with a route; `menu` → `SettingsMenu` inside the panel (420, right-aligned, clamped to the rows area) |
| Move mode (S9) | `MoveModeRow(…, width)` takes the row's width (464) in the panel; its "↑↓ · OK · Back" hint moves to the band while `ui.move != null` |
| Key capture | `CaptureOverlay` centred with its .55 scrim |
| `PageModel.placeholder` / `subtitle` | 22 px, up to 3 / 2 lines |
| Paged lists | unchanged (`PlaceholderRow` 64) |
| About header | compact: no wordmark |
| The Test's progress row, searching dots | not in the panel (their routes expand, §2.6) |
| Sports interval row | its detail goes to the band, cut at 3 lines; full text via the Settings key (expand) |
| `playback.restart_now`, `interface.restart_now`, `about.reset` | as today (they restart the app) |

---

## 3. Entry points

| From | Today | With this design |
|---|---|---|
| Quick bar → Settings (FULL) | full-screen Root | **panel** (last page if closed < 10 min ago), PIN-gated |
| Remote Settings key in FULL | full-screen Root | **panel** (Q2); a second press inside the panel expands |
| Error card OPEN_SETTINGS | full-screen, detached | **panel, detached**; BACK → the card (Q3) |
| Error card OPEN_EXTERNAL fallback (`Section(PLAYBACK)`) | full-screen, detached | **panel, detached** |
| Score sheet | no Settings link (F18) | none added; a future link calls `PlayerEffects.openSettings(Section(SPORTS))` and gets the panel by the same rule |
| Guide gear, Groups shortcut, guide context menu, sync chip, empty state, first run | full screen | **unchanged** |
| Panel → full screen | – | Settings key anywhere; ☰ on the root list (hint "☰ Full screen" in the root header); any expand route |

---

## 4. Live effect

### 4.1 Timings today (registry) and what the panel can show

| Timing | Rows | Today | Stage A | Stage B |
|---|---|---|---|---|
| LIVE | AFR; reconnect, failover, after standby, HDR preview, info bar, number wait; interface rows except text size; remote; network; guide interval/hour/shift/posters; Sports (show, corner, delay, interval, size, style); parental; channel lists | on the store write | **visible at once** where the player shows it: AFR, the whole score bug, the clock. Event-driven rows act on the next event; guide-only rows show in the guide | same |
| REPREPARE | `playback.buffer`, `audio_output`, `decoder`, `tunneling`, `live_speed` | once, when Settings closes | **re-prepared 400 ms after the change** with today's applier; honest notice (§4.2) | outcome-based notice, `CHANNEL_OVERRIDE`, tunneling reset (§4.3) |
| NEXT_TUNE, connection | `playlist.user_agent`, `headers`, `stream_format`, `ignore_tls` (+ their sub-rows, F27) | next tune | "Applies when you change channel" | **paced restart** of the playing playlist's channel (§4.3) |
| NEXT_TUNE, other | `playback.aspect`, `audio_language`, `subtitles`, `external_player`, `timeshift`, `timeshift_location`; `playlist.backup_servers`, `max_connections`, `catchup_*`, `padding_*` | next tune | "Applies when you change channel"; aspect, audio, subtitles add "For this channel use the quick bar" | unchanged, except backup servers and max connections restart **on the error card** (S7) |
| NEXT_LAUNCH | `codec_queueing` ("Decoder hand-off"), `audio_buffer`, `on_launch`, `update_on_start`, `open_guide_on` | next start | "Applies the next time The Box starts" | same (live version deferred, §7) |
| RESTART | `ffmpeg`, `text_size`, `about.reset` | next start or Restart now | same | same |
| NEXT_REFRESH / NEXT_EPG_IMPORT | playlist / guide data rows | next refresh / import | same | same |

### 4.2 Stage A: re-prepare through today's applier, reported honestly

In `SettingsEffectsImpl.onIntent`, after `runIntent`, while `presentation == PANEL`: a row whose registry effect is
REPREPARE (prefix-matched, §4.3) → `scheduleApply()`. `scheduleApply` cancels the pending job and runs on `main` after
`APPLY_DEBOUNCE_MS`. Nothing else is scheduled in stage A.

Contract (stage A commit 0, `ArchiveContracts.kt`; the enum is append-only so stage B can add values):
```kotlin
enum class PanelApplyResult { REPREPARING, WAITS_FOR_RESUME, NOTHING_PLAYING, CARD_SHOWN }
interface LiveSettingsApplier {
    fun apply()
    fun applyFromPanel(): PanelApplyResult { apply(); return PanelApplyResult.REPREPARING }
}
```

`LiveSettingsApplierImpl.applyFromPanel()` reads `controller.state.value` and `archive.status.value` (no controller
change):
1. `Idle` or `Stopped` → NOTHING_PLAYING, no call.
2. `PlaybackState.Error` (the card) → CARD_SHOWN, no call (`reapplySettings` is a no-op there and `retry()` would reset
   the limiter, S7).
3. Archive phases exactly as `apply()`: playing → `archive.reapply(playhead)` → REPREPARING; LOCAL paused → reader
   re-prepare → WAITS_FOR_RESUME; other paused → WAITS_FOR_RESUME.
4. Live `Playing(paused = true)` → `reapplySettings()` (it decides) → WAITS_FOR_RESUME.
5. Otherwise `reapplySettings()` → REPREPARING.

Notices (`SettingsEvent.Notice`, band, 3 s):
- REPREPARING: "Re-preparing the stream. A choice made for this channel on the quick bar still wins."
- WAITS_FOR_RESUME: "Applies when playback resumes."
- CARD_SHOWN: "Saved. Close Settings and choose Retry to try it."
- NOTHING_PLAYING: none.

The panel **never** says "Applied" in stage A: `reapplySettings()` returns `Unit` and cannot tell a shadowed value
(F13, B1).

Close: `onClosed` must not re-prepare twice. The effects keep `skipNextReapply`, set when a panel apply ran, cleared by
a REPREPARE intent in FULL_SCREEN (after an expand). `onClosed` applies only when `REPREPARE in timings &&
!skipNextReapply`, then resets it; a pending debounced apply runs at once on `onClosed`. With the card shown (CARD_SHOWN)
the flag is not set, so leaving Settings still calls `apply()` as today.

Connection rows (user agent, headers, stream format, Ignore TLS) are saved and show "Applies when you change channel".
On the error card the user's own Retry applies them (a new cycle re-resolves, F10).

### 4.3 Stage B: controller outcome and paced restart (after `playback` merges)

**Contracts (stage B commit 0).**
```kotlin
// PlaybackApi.kt
enum class OverrideKind { DECODER, BUFFER, TUNNELING }
sealed interface ReapplyOutcome {
    data object Applied : ReapplyOutcome
    data class Shadowed(val kinds: Set<OverrideKind>) : ReapplyOutcome  // the global value is not the effective one
    data object NotApplicable : ReapplyOutcome                         // idle, stopped, archive, card, paused unbuffered
}
fun reapplySettings(): ReapplyOutcome = ReapplyOutcome.NotApplicable   // was Unit
/** (S7) A new cycle of the current target for a settings change: a user tune (new visit), keeps the re-resolve limiter. */
fun restartForSettings(): Boolean = false
/** (B1) The channel's effective overrides now, for the band. */
val channelOverrides: StateFlow<Set<OverrideKind>> get() = NO_OVERRIDES
// ArchiveContracts.kt: PanelApplyResult += APPLIED, CHANNEL_OVERRIDE, RESTARTED, WAITS_FOR_LIVE, OTHER_PLAYLIST
fun restartFromPanel(playlistId: Long?, cardOnly: Boolean): PanelApplyResult = PanelApplyResult.NOTHING_PLAYING
```

**Controller (B1, S7, S12).**
- `reapplySettings()` computes the outcome from the same expressions as `applySwitches` (F13): `Shadowed(DECODER)` when
  `c.decoderOverride` or `c.memory.videoDecoder` is set, `Shadowed(BUFFER)` when `c.memory.bufferMode` is set,
  `Shadowed(TUNNELING)` when tunneling is on in settings and off for the channel.
- An explicit user change to `playback.tunneling` (any presentation) clears this visit's learned tunneling-off and the
  damping for the playing channel, before the re-prepare (B1.3). Called through a new `PlaybackController`
  `onUserSettingChanged(row)` hook, invoked by `SettingsEffectsImpl` for that row.
- `restartForSettings()` = `startCycle(c.specForRestart(), userTune = true)` **without** `limiter.reset` (S7); never on a
  clip; respects the guard. Eligible for the branch's Auto TS retry.
- On the branch, check that a re-prepare with `reuseResolved` after an in-place HLS renewal reopens the panel URL (which
  redirects afresh), not an expired edge URL (S12).

**Applier.** `applyFromPanel()` maps `Applied` → APPLIED, `Shadowed` → CHANNEL_OVERRIDE, `NotApplicable` → as stage A.
`restartFromPanel(playlistId, cardOnly)`:
1. nothing tuned → NOTHING_PLAYING; the tune's `target.identity.playlistId != playlistId` → OTHER_PLAYLIST;
2. behind live (archive session, or a LOCAL session not at the edge) → WAITS_FOR_LIVE, no restart (S6);
3. `cardOnly` and no card → none (the row keeps its NEXT_TUNE text);
4. pacing: at most one restart per `PANEL_RESTART_MIN_GAP_MS` (5 000); a request inside the gap is held and runs once
   at the gap's end (trailing);
5. `controller.restartForSettings()` → RESTARTED.

**Rows (new `PanelRetuneRows.kt`, stage B).** `retunes(id)` and `retunesOnCard(id)` match by prefix
(`key == id || key.startsWith("$id.")`, S4):
- restart: `playlist.user_agent`, `playlist.headers`, `playlist.stream_format`, `playlist.ignore_tls`;
- restart only while the card shows: `playlist.backup_servers`, `playlist.max_connections`;
- never: audio language, subtitles (S5), timeshift rows (S7), catch-up rows (S6), `channel.*` (N3).

**Write ordering (S3).** The playlist intents for those six rows **await** their write (`withContext` instead of
`launch`) before `onIntent` returns, so the restart reads the new row (`PlaylistPages.kt`).

**Notices (stage B).** APPLIED "Applied to this channel"; CHANNEL_OVERRIDE "This channel uses its own decoder (quick
bar). Change it there." (buffer: "its own buffer, learned for it"; tunneling cannot shadow after B1.3); RESTARTED
"Restarting the channel to apply…"; WAITS_FOR_LIVE "Applies when you return to live or change channel"; OTHER_PLAYLIST
"Applies to channels of this playlist". While a REPREPARE row is focused and `channelOverrides` contains its kind, the
band's timing line reads the CHANNEL_OVERRIDE text (B1.2): `WpaPageEnv.channelOverrides()` feeds WpaPages, which
rebuilds on its change.

### 4.4 The timing line (S8)

The band's first line is the **timing line**, drawn by the panel from `row.effect` and `SettingsPanelRules.timing(row)`,
in its own style (§5.6). Builders **omit** the appended timing sentence when `ctx.panel` (`WpaPages.row()`, one branch).

| `PanelTiming` | Stage A text | Stage B text |
|---|---|---|
| AT_ONCE (LIVE) | none (no line) | none |
| REPREPARE | "Re-prepares the stream when you change it" | "Applies now" (or the CHANNEL_OVERRIDE text) |
| CHANGE_CHANNEL (NEXT_TUNE) | "Applies when you change channel" | connection rows: "Restarts this channel to apply" |
| NEXT_START (NEXT_LAUNCH) | "Applies the next time The Box starts" | same |
| RESTART_APP (RESTART) | "Applies after a restart (Restart now)" | same |
| NEXT_UPDATE / NEXT_GUIDE_UPDATE | "Applies at the next playlist / guide update" | same |

WP-B's playlist rows (F14) whose whole description is `SetupText.EFFECT_NEXT_TUNE` would repeat the timing line. Stage A
does not edit `PlaylistPages.kt` (the `playback` branch changes it): the band drops a description that **equals** the
full-screen NEXT_TUNE sentence for that row. Stage B removes the sentence in `PlaylistPages` under `ctx.panel` and the
drop rule goes.

---

## 5. Layout

Design px (1 px = screen width / 1920), `BoxDesignScale`; text through `GuideUi.sans/mono`, scaled by `UiTextScale`
(Default 1, Largest 1.2). `object SettingsPanelUi` in `SettingsPanel.kt`.

### 5.1 Panel box

| Item | Value |
|---|---|
| Box | x 1376, y 48, w 496, h 880 (`PlayerUi.PICKER_*`; right 1872, bottom 928, 24 above the quick bar) |
| Fill / border / radius | `PlayerUi.PanelFill` (canvas .86), 1 px `PlayerUi.Border09`, `Radii.Panel` 24; no blur |
| Padding | 16 left/right, 20 top, 16 bottom; inner width 464 (x 1392…1856) |
| Clip | rows clip to the panel's shape; the panel is a key-sink child, never an ancestor of `VideoLayer` |
| Show / hide | 150 ms fade of its own content, snapped when `lowEnd` |

### 5.2 Header (breadcrumb)

| Item | Default | Largest |
|---|---|---|
| Row | y 68, height 40 (grows) | ~48 |
| "←" | 26/600 Fg, inner x 12 | 31 |
| Parent title | 22/500 Fg55, max 160, ellipsis; Root's is "Settings" | 26 |
| Divider | 1 × 24, White .22, 12 each side | – |
| Current title | 24/600 Fg, the rest, ellipsis | 29 |
| At Root | "Settings" 24/600 alone; right-aligned "☰ Full screen" 22/400 Fg55 | – |
| Hairline | 12 below, 1 px White .12 | – |

The left part names the **parent** (where BACK goes): "← Settings | Playlists" at a section page, as in the mock-up. A
detached bottom page shows its own title only.

### 5.3 Page title

Under the hairline +16: `page.title` 28/600, one line; `subtitle` 22/400 Fg60, up to 2 lines. Not at Root. Rows start
12 below: y 184 without a subtitle, y 132 at Root.

### 5.4 Compact rows (`SettingsRowView(…, compact = true)`)

| Item | Default | Largest |
|---|---|---|
| Width | 464 | 464 |
| Min height | 64 one line; 92 two lines; rows grow (`heightIn`) | ~64 / ~100 |
| Gap / padding / radius | 6 / 16 h, 10 v / 12 | same |
| Label | 26/500 Fg92 (focused: canvas), up to 2 lines | 31 |
| Second line | value 22/400 Fg65 (mono for mono values), 1 line, `MiddleEllipsis` for URLs; badges lead it; Info lines up to 3 (account card 2, N8) | 26 |
| Trailing | toggle 56 × 30; chevron "›" 26 Fg45; destructive dot 10 | – |
| Focus | FG fill, 3 px accent ring, dark text | – |
| Disabled | alpha .4; the reason in the band | – |
| Not inline | the description (band) | – |

### 5.5 Inline option list

Indent 24; option rows 56 (label up to **2 lines**, N7; 76+ with a `detail`, 1 line under the label, 22 Fg55); radio
or check 18; gap 14; label 24/400. `bringIntoView` as today.

### 5.6 Description band (S8)

| Item | Default | Largest |
|---|---|---|
| Position | bottom of the inner box, y 772…912; hairline on top | y ~748…912 |
| Height | 12 + 4 × 29 + 12 = 140 (`BAND_LINES = 4`) | 12 + 4 × 35 + 12 ≈ 164 |
| Line 1 | the **timing line** (§4.4), 22/500 Fg92, a 4 × 22 accent bar at the left; absent for AT_ONCE and NONE | 26 |
| Lines 2–4 (or 1–4 without a timing line) | the focused row's `secondLine(row)` (description or disabled reason) minus any timing sentence, 22/400 Fg70, ellipsis | 26 |
| Notice | `ui.notice` replaces the whole band for 3 s, 22/500 Fg92, accent bar, up to 4 lines | – |
| Move mode | the move hint replaces lines 2–4 while `ui.move != null` (S9) | – |

The band keeps its height when empty. Rows area: 184 … 764 = 580 at Default (about 9 one-line or 6 two-line rows);
about 540 at Largest (about 5 two-line rows). Root's band is empty unless a notice shows.

### 5.7 Scrolling

Unchanged: the machine's `top` (`VISIBLE_ROWS = 8`) plus `revealCursor`; `LazyColumn(userScrollEnabled = false)`, keys
from `SettingsRowKeys`; no scroll bar.

### 5.8 Quick bar

Visible, no auto-hide. The Settings pill is **active, not focused** (`QuickBarState.panelOpen`): fill White .16, 2 px
accent border, FG text, `stateDescription` "Open". On close it takes the keys, focused on Settings, and hides after 8 s.

### 5.9 Score bug

`scorePlacementInput` (`FullScreenHost.kt`): `picker = ui.picker != null || state.settingsPanel`. The panel's box is the
picker box (F16); no `:core` sports change. The score-sheet restyle did not touch `FullScreenHost.kt`, so there is no
ordering constraint. Sports rows are LIVE: size, style and corner changes show on the bug while editing.

### 5.10 Stats overlay ("Stream info")

Kept (FULL is never left). At (64, 56), at most 600 × 508: no overlap with the panel. It cannot be switched on from
inside the panel with the default remote (N4): **turn Stream info on from the quick bar, then choose Settings**. The
Stream info pill shows ✓. This is the recommended way to judge a decoder or buffer change.

### 5.11 Info bar, toasts, reminder card

Info and scrub bars are not shown. Toasts bottom-left over the quick bar (156). Reminder card:
`reminderEndFor(shown, pickerOpen || ui.settingsPanel)` → right offset 568; it stays modal in FULL; "Switch now" tunes
and the panel stays open.

### 5.12 Error card, playback failure, channel change

- Playback fails while the panel is open: the card shows centred (x 600…1320), unfocused; the panel keeps the keys. A
  change made now: stage A notice CARD_SHOWN; stage B restarts for the card-only rows and connection rows (paced). BACK
  out of the panel focuses the card.
- Opened from the card (detached): the same; BACK at the bottom returns to the card.
- Channel changes while open (reminder, failover, PIN-granted tune): the panel stays; quick bar values refresh; later
  applies target the new channel (stage B: OTHER_PLAYLIST when the playlist differs).
- Paused frame: not dimmed while the panel is open; the paused card draws under the panel.
- Subtitles unchanged (a long line can pass under the panel's left edge); probing line and stopped hint do not overlap.
- Number entry, group card, exit hint cannot open; an open one is closed when the panel opens.

### 5.13 Where it is drawn

`AppRoot`, in the key sink, after `FullScreenHost`, when `shown == FULL && ui.settingsPanel && settingsMachine != null`:
`SettingsPanel(settingsMachine, lowEnd)`. Above the player overlays, below the reminder card, the PIN pad and the toast.
`SettingsPanel` draws the panel box (header, title, rows, band, context menu), then the centred card overlays
(`ConfirmCard`, `NumberPad`, `TextEditorOverlay` wired as in `SettingsScreen`, `CaptureOverlay`).

---

## 6. Performance and rules

- No blur. No `graphicsLayer`, alpha or clip on an ancestor of `VideoLayer` (`RootTreeRuleTest` gets a panel case).
- FULL hole and viewport throughout: opening the panel toggles no track (unlike full-screen Settings, F3).
- `SettingsPanel` collects `machine.ui` itself; `AppRoot` reads `GuideUiState.settingsPanel` and the expand flag
  (`distinctUntilChanged`); the player state does not change while the panel is open, so `FullScreenHost` does not
  recompose per settings key.
- Fire TV Stick: about ten compact rows; no per-frame work; no infinite transition inside the panel (the searching dots'
  route expands, N2); section previews build on `Dispatchers.Default` as today (N1). Each REPREPARE costs one
  re-prepare, bounded by the 400 ms debounce; stage B restarts are bounded to one per 5 s.
- TalkBack/VoiceView: `paneTitle` "Settings"; an announcement on open and on expand (N10); rows keep `rowDescription`;
  notices are announced by the machine.
- Text ≥ 22 design px.
- Logs: `reapply from panel: <result>` / `restart from panel: <result>`, no URLs or values (U-L10).

---

## 7. Deferred (first version scope = the review's "Keep" list)

| Item | Why deferred |
|---|---|
| Live "Decoder hand-off" and "Audio buffer" (old WP-6) | needs a dynamic codec-adapter factory and buffer-size provider in the player; F31 shows it is feasible; its own playback review |
| `ARCHIVE_RETUNE_ROWS` (catch-up mode, zone, padding, days) | an archive session re-requests from its cached context and does not re-read them (S6, F29) |
| `channel.*` restarts (decoder, tunneling, AFR) | `Channel(identity)` is not reachable from the panel (N3) |
| Timeshift and timeshift-location restarts | would destroy the rewind buffer and start storage preparation for a next-tune setting (S7) |
| Backup servers / max connections restarts outside the error card | they do not change the current connection (S7) |
| Audio language / subtitles restart, and `applyTrackPreferences()` | the quick bar's Audio and Subtitles pickers already do it live; per-channel memory wins (S5, F28) |
| Section-preview skip at Root | saves little, costs a placeholder frame per section (N1) |
| The storage walk and Licences in the panel, and the `ProgressTrack` width change | no visible effect on the video; infinite dots; a transfer that skews the stats (N2) |
| ☰ expands on rows without a context menu | a long OK is the same action and would expand by accident (S11) |
| Restoring the stats overlay after an expand | small but needs the player to carry the flag across `leaveFull` (N5) |
| Seek keys passed through while time-shifted | optional; not needed to judge a setting (N11) |
| A panel-only "Stream info" toggle | quick bar first is enough for v1 (N4) |
| Zapping from the panel, a panel from the guide, live text size, a preview pane | out of the owner's ask; text size is per process (F15) |

---

## 8. Risks

| # | Risk | Handling |
|---|---|---|
| R1 | Stage A re-prepares a shadowed value (visible rebuffer, nothing changes) | the notice says a channel's own choice still wins; stage B reports CHANNEL_OVERRIDE |
| R2 | A re-prepare on every toggle on a slow box | 400 ms debounce, today's path |
| R3 | Restart storms on a one-connection account | stage B: limiter kept, one restart per 5 s, connection rows only |
| R4 | Keys reaching the wrong machine | one routing point (`panelKey`) before the player; the GUIDE key table (S1) |
| R5 | Panel flag and player layer drifting apart; a visit without `onLeave` | `publish()` invariant + idempotent `settingsPanel` every dispatch (S2) |
| R6 | Re-entrant expand | checked after `act` returns, or posted from `AppRoot` (S10) |
| R7 | Text editor focus while mode is FULL | `textInputActive` covers the panel; IME on Fire OS over FULL is an emulator/device item (§12 state 6) |
| R8 | Merge with the `playback` branch | stage A's edits to `PlayerContracts.kt`, `SettingsMachineImpl.kt` and the settings contracts are additive and in other regions; `PlaylistPages.kt`, `SetupText.kt`, `PlaybackApi.kt`, `PlaybackControllerImpl.kt` are stage B only (§11.3) |
| R9 | Double re-prepare on close | `skipNextReapply` |

---

## 9. Open questions (lead defaults in force until the owner answers)

| # | Question | Default (lead ruling 2026-11-26) |
|---|---|---|
| Q1 | Should the panel restart the channel by itself for settings that need it? | **Yes, automatically** (stage B), paced and limited: connection rows only, never behind live, at most one per 5 s, not for audio language or subtitles (S5–S7) |
| Q2 | Should the remote's Settings key while watching open the panel? | **Yes**; a second press expands |
| Q3 | Should an error card's Settings button open the panel beside the card? | **Yes**; BACK returns to the card |
| Q4 | CH± in the panel: page the list or change channel? | **Page the list** |
| Q5 | Reopen at the root or at the last page? | **Last page if the panel was closed within 10 minutes**, else the root list |

---

## 10. Tests

**Stage A.**

`:core` (JVM):
- `GuideSettingsPanelTest`: quick bar Settings in FULL → panel (mode FULL, `present(PANEL)` before `open`); from GUIDE →
  full screen; Lock settings → PIN → panel; Settings key → panel, second press → expand; `OpenSettingsDetached` → panel
  detached, BACK → card focused; BACK at root → closed, `onLeave` once; OPEN_GUIDE / EXIT; PLAY_PAUSE reaches the
  player; `ExpandSettings` → SETTINGS, same route, no `onLeave`, BACK → FULL; ExpandSettings when closed or on a
  non-expanding route → nothing; a key that pushes `AddSource` expands after `act` returns (S10); `keyContext` GUIDE
  while open (S1); `textInputActive`; reopen within 10 min at the last page, after 10 min at Root, never for detached
  visits (Q5); **every path into GUIDE, SEARCH and SETTINGS with the panel open calls `onLeave` exactly once**, including
  `commitEntry(FULL)` and `cancelPin` (S2); the player hears `settingsPanel(flag)` after every dispatch.
- `PlayerSettingsPanelLayerTest`: SETTINGS_PANEL publishes `quick` with `panelOpen` and focus on SETTINGS; no auto-hide;
  no passive info bar on a new channel's Tuned; `syncError`, `enterFull` and `closeAll` keep the layer while open; the
  card has `focused = -1`; close → QUICK_BAR + 8 s timer, or ERROR; PLAY_PAUSE handled; no dim; `quickSelect(SETTINGS)`
  re-entrant open leaves SETTINGS_PANEL with no timer (N9).
- `SettingsMachinePanelTest`: `present` publishes, clears the page cache and reloads; path, cursor and overlays kept;
  the machine dispatches nothing to the guide on navigation; `SettingsBackLadderTest` green in both presentations.
- `SettingsPanelRulesTest`: `expands` for every route (exhaustive `when`); `expandKey` only for the Settings key and
  CONTEXT_MENU at the root list, not on a row (S11); `timing(row)` for each effect.

`:ui` (Robolectric):
- `KeyRouterPanelTest` (S1): with the panel open, a held BACK from depth 3 leaves FULL showing the quick bar; LEFT/RIGHT
  act on key-down.
- `SettingsEffectsPanelApplyTest`: a REPREPARE intent (and a REPREPARE sub-row) in PANEL → `applyFromPanel()` once after
  400 ms; two toggles in 400 ms → once; a connection row → no apply; FULL_SCREEN → no immediate apply; `onClosed` after
  a panel apply does not apply again, but does after CARD_SHOWN; result notices; `ReapplyOnExitTest` and
  `SettingsEffectsOrderTest` unchanged.
- `SettingsPanelBoundsTest`: the box, header, hairline, rows, band (140 / ~164) inside it; no row wider than 464; option
  list and context menu inside; **move mode inside (S9)**; Default and Largest.
- `SettingsCompactRowTest`: value under the label; toggle without a second line; masked dots; badges; About without the
  wordmark; `MoveModeRow` at 464 with the hint in the band (S9); option labels on 2 lines (N7); the account line wraps
  (N8); a description equal to the NEXT_TUNE sentence is dropped from the band.
- `QuickBarPanelStyleTest`; `RootTreeRuleTest` panel case; `scorePlacementInput` with `settingsPanel`; the reminder
  offset; the expand collector dispatches once per expanding route.

`:app`: `LiveSettingsApplierImplTest.applyFromPanel` — idle, stopped, error card (no call), archive playing, LOCAL
paused, live paused, live playing.

**Stage B.**
- `:player`: `ReapplyOutcomeTest` (Applied; Shadowed for decoder override, memory decoder, memory buffer, tunneling off;
  NotApplicable cases); `RestartForSettingsTest` (user tune, limiter **not** reset, new visit clears per-visit
  tunneling, guard respected, never a clip); a user tunneling change clears the learned visit state (B1.3); HLS renewal
  + `reuseResolved` reopens the panel URL (S12).
- `:app`: `restartFromPanel` — other playlist, behind live → WAITS_FOR_LIVE, card-only rows, the 5 s gap with a
  trailing restart.
- `:core`: `PanelRetuneRowsTest` — prefix match for `.custom`, `.confirmed`, `.h.N`, `.s.N` (S4); every id is
  NEXT_TUNE in the registry.
- `:core`: `PlaylistPagesAwaitTest` — the six intents return after the write (S3).

Gate per stage: `./gradlew :core:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug lintAll`
(stage B adds `:player:testDebugUnitTest`); targeted tests per WP first, ask before the full run.

---

## 11. Work packages

### 11.1 Stage A (on `main` now)

| Package | Files owned | Depends on | Model |
|---|---|---|---|
| **A0 commit 0 + 0b** (lead, done) | `SettingsMachine.kt` (`SettingsPresentation`, `presentation`, `present`, `announceOpen`); `SettingsPages.kt` (`PageContext.panel`); `GuideStateContracts.kt` (`settingsPanel`, `ExpandSettings`, `keyContext`); `PlayerContracts.kt` (`SETTINGS_PANEL`, `QuickBarState.panelOpen`, `settingsPanel()`); `LiveSettingsApplier.kt` (`PanelApplyResult`, `applyFromPanel`); `SettingsPanelRules.kt` (signatures, constants, `PanelTiming` **and the frozen bodies**, 0b) + `SettingsPanelRulesTest`; `SettingsTexts.kt` (13 `PANEL_*` keys); `ResourceSettingsTexts.kt` + `strings_settings.xml` (their mapping and strings); stub `ui/.../settings/SettingsPanel.kt`; fakes: `FakeSettings.kt` (testFixtures), `FakeState.kt` (`FakeGuideStateMachine`), `FakePlayer.kt`, `:ui` `FakeSettingsUiMachine`; neutral branches (Commit 0 rulings item 9) | – | Opus |
| **A1 guide, router, root glue** | `GuideStateMachineImpl.kt`; `KeyRouterImpl.kt`; `AppRoot.kt` (draw `SettingsPanel`, reminder offset, the posted expand collector); `FullScreenHost.kt` (the `picker` line); tests `GuideSettingsPanelTest`, `KeyRouterPanelTest`, the `RootTreeRuleTest` case, placement and reminder cases, expand collector test; **`ComposedFullBackLadderTest`** (the SETTINGS_PANEL case: it needs the composed guide with a settings machine attached; green once A2 lands) | A0 | Opus |
| **A2 player machine** | `PlayerStateMachineImpl.kt`; `PlayerSettingsPanelLayerTest`; **`PlayerKeysTableTest`** (+ `player_keys.csv` rows ending in SETTINGS_PANEL) and **`PlayerFiveKeyReachabilityTest`** (drop or keep the commit-0 skip) | A0 | Opus |
| **A3 settings machine, effects, applier** | `SettingsMachineImpl.kt` (`present`, cache clear, `announceOpen`); `SettingsEffectsImpl.kt` (`ctx.panel`, `scheduleApply`, `skipNextReapply`, notices); `app/.../LiveSettingsApplierImpl.kt` (`applyFromPanel`); tests `SettingsMachinePanelTest`, `SettingsEffectsPanelApplyTest`, `LiveSettingsApplierImplTest`. Not `SettingsPanelRules.kt` (lead, frozen in 0b) | A0 | Opus |
| **A4 texts** | `WpaPages.kt` (omit the timing sentence when `ctx.panel`); builder tests in both presentations; may reword the 13 `PANEL_*` strings in `strings_settings.xml` (no new keys without the lead) | A0 | Sonnet |
| **A5 panel UI** | `SettingsPanel.kt` (body, `SettingsPanelUi`); `SettingsRowViews.kt` (`compact`); `MoveModeRow.kt` (`width`); `QuickBar.kt` (active pill); tests `SettingsPanelBoundsTest`, `SettingsCompactRowTest`, `QuickBarPanelStyleTest` | A0 | Sonnet (mechanical, under §5) |

A1–A5 run in parallel after A0; their file sets are disjoint. Integration (lead): emulator checklist states 1–8, code
review pass.

### 11.2 Stage B (after `playback` merges into `main`)

| Package | Files owned | Depends on | Model |
|---|---|---|---|
| **B0 commit 0** (lead) | `PlaybackApi.kt` (`OverrideKind`, `ReapplyOutcome`, `reapplySettings` return type, `restartForSettings`, `channelOverrides`, `onUserSettingChanged`); `LiveSettingsApplier.kt` (append `PanelApplyResult` values, `restartFromPanel`; Commit 0 ruling 2); `WpaPageEnv` in `WpaPages.kt` (`channelOverrides()` default); fakes in `:player` and `:app` tests | `playback` merged; stage A merged | Opus |
| **B1 controller** | `PlaybackControllerImpl.kt`; `:player` tests | B0 | Opus |
| **B2 panel restart and honest outcome** | new `PanelRetuneRows.kt`; `SettingsPanelRules.kt` (stage-B timing texts; the only stage-B owner of the file); `SettingsEffectsImpl.kt` (restart scheduling, `onUserSettingChanged` for tunneling); `LiveSettingsApplierImpl.kt` (outcome mapping, `restartFromPanel`, pacing); `app/.../WpaPageEnvImpl.kt`; `WpaPages.kt` (CHANNEL_OVERRIDE line under `ctx.panel`); `PlaylistPages.kt` (S3 awaits, omit the sentence under `ctx.panel`); `SetupText.kt`, `strings_setup.xml`, `strings_settings.xml` (stage-B texts); `SettingsPanel.kt` (drop the band's description-drop rule); `:core`/`:app`/`:ui` tests | B0 (runs against B1's contract with a fake) | Opus |

Integration (lead): re-run emulator states 2, 3 and 8 with the stage-B expectations; code review pass; record the
reconciled decision in `docs/00-master-plan.md` or `docs/decisions/2026-11-26-player-settings-panel.md`.

### 11.3 Overlap with the `playback` branch

The branch changes `PlayerContracts.kt` (StatsFacts, `TunnelingOff`, `StatLabel`), `SettingsContracts.kt`
(`ChoiceOption.reselectable`) and one line of `SettingsMachineImpl.choiceKey`. Stage A edits other regions of
`PlayerContracts.kt` and `SettingsMachineImpl.kt`, and does not edit `SettingsContracts.kt`: textual merges. Stage A
does not touch `PlaybackControllerImpl.kt`, `PlaybackApi.kt`, `PlaylistPages.kt`, `SetupText.kt`, `StatsOverlay.kt`,
`PlayerText.kt`, `strings_player.xml` or `strings_setup.xml`, all of which the branch changes.

---

## 12. Emulator checklist (eight compact states)

Fake panel `python3 tools/fakepanel/fakepanel.py --port 8080`, one Xtream playlist, one live channel playing.

1. Quick bar → Stream info on, then Settings: panel at Root ("Settings", "☰ Full screen"), Settings pill active, stats
   at left, score bug out of the box; bounds as §5.1.
2. Playback → Video decoder → Software on a channel with the quick bar's Decoder on Auto: one re-prepare, notice
   "Re-preparing the stream…", stats decoder line changes; log one `reapply_settings`. (Stage B: notice "Applied…"; with
   the Decoder chip set: "This channel uses its own decoder…" and no rebuffer claim.)
3. Playlists → the playlist → Stream format: band "Applies when you change channel", no restart. (Stage B: "Restarting
   the channel…", one new cycle through the broker; a second change within 5 s gives one trailing restart.)
4. Hold BACK three pages deep: the panel closes once and FULL stays, quick bar focused on Settings, hides after 8 s.
5. "Lock settings" on: Settings chip → PIN pad over the video → panel; reopen within 10 min returns to the last page.
6. Playlist page → User agent: editor card centred with the Fire OS IME; Back cancels; cursor kept.
7. Playlists → Add playlist: expands to full-screen AddSource; BACK twice → FULL. A long OK on a toggle does not expand.
8. Text size Largest, a stream fault from the fake panel: card centred, unfocused; change Buffer → "Saved. Close Settings
   and choose Retry…"; Channels → Groups → move mode fits the panel; band not clipped; BACK closes to the focused card.
   (Stage B: Stream format change restarts and the card clears if it works.)

---

## 13. Settled by the review, and still open

Settled (review "three facts"):
- `retry()` re-resolves with the new playlist settings (new cycle, Room read, no resolver cache). Caveats: the write
  race (S3, stage B); a learned HLS output still wins over Auto.
- Audio language and subtitle preferences apply once per cycle, not on a re-prepare (S5).
- A re-prepare re-creates the AudioTrack on every device (F31): a live "Audio buffer" is feasible when un-deferred.

Still open:
- Fire OS IME behaviour over FULL (state 6).
- The rebuffer cost of a decoder re-prepare on a Fire TV Stick (state 2, measure).
- One-line section summaries for the band at Root do not exist (the band is empty there).

---

## 14. Review changes (2026-11-26)

| Finding | Ruling | Where it landed |
|---|---|---|
| B1 shadowed values reported as applied | accepted | §0 items 10–11; §4.2 (stage A never says "Applied"); §4.3 (`ReapplyOutcome`, CHANNEL_OVERRIDE, band line, tunneling reset); §11.2 B0–B2 |
| S1 PLAYER key table, held BACK | accepted | §2.2 (`keyContext`, router, PASS_THROUGH = PLAY_PAUSE, no `horizontalRepeat` override); §2.4; §10 `KeyRouterPanelTest`; §12 state 4 |
| S2 paths that leave FULL without closing | accepted | §2.3 "One source of truth"; §2.2 player keeps the layer; §10 `onLeave`-once test |
| S3 fire-and-forget playlist writes | accepted (stage B) | §4.3 "Write ordering"; §11.2 B2 `PlaylistPages.kt` |
| S4 sub-row ids | accepted (stage B; REPREPARE matching in stage A too) | §4.2, §4.3 `PanelRetuneRows` prefix match; §10 |
| S5 audio language / subtitles | accepted | §4.1, §4.3 "never"; §7 |
| S6 catch-up rows and behind-live | accepted | §4.3 step 2 (WAITS_FOR_LIVE); §7 |
| S7 pacing and the limiter | accepted (stage B) | §4.2 (no `retry()` in stage A); §4.3 (`restartForSettings`, 5 s gap, narrowed rows, card-only rows); §9 Q1 |
| S8 band truncates the timing | accepted | §4.4 timing line; §5.6 4-line band; §11.1 A4 |
| S9 `MoveModeRow` width | accepted | F23; §2.7; §5.6; §11.1 A5; §10 |
| S10 re-entrant expand | accepted | §2.1 (machine dispatches nothing); §2.2; §2.6 "When expanding is checked"; §11.1 A1 |
| S11 long OK expands | accepted | §2.6 `expandKey`; §3; §7; §12 state 7 |
| S12 merge order | accepted, re-planned per lead | stages A/B; §11.3; S12's HLS renewal check in §4.3 and §10 stage B |
| N1 keep the section preview | adopted (Cut list) | §2.1; §7 |
| N2 expand the storage walk and Licences | adopted (Cut list) | §2.6 table; §7 |
| N3 drop `channel.*` restarts | adopted (Cut list) | §4.3; §7 |
| N4 Stream info from the quick bar | adopted | §2.2; §5.10; §12 state 1 |
| N5 losses on expand | partly: page-cache clear adopted; stats restore deferred | §2.1; §2.3; §7 |
| N6 LEFT at a detached bottom | adopted (table fixed) | F5; §2.4 |
| N7 option labels 2 lines | adopted | §5.5 |
| N8 account line wraps | adopted | §2.7; §5.4 |
| N9 re-entrant open test | adopted | §2.2; §10 |
| N10 announce on open | adopted | §2.3; §6 |
| N11 seek keys | deferred | §7 |
| "Keep" / "Cut" lists | first version = Keep | §0, §4, §7 |

---

## Commit 0 rulings (2026-11-26)

Where commit 0 (A0) had to choose, the choice that keeps A1–A5 disjoint and today's behaviour unchanged was taken.

1. **`announceOpen()` is a `SettingsMachine` member** (default no-op), not a `SettingsShellLink` one (§2.3 said link).
   The guide holds a `SettingsMachine` and never casts it; `SettingsMachineImpl` implements both interfaces, so A3's work
   is the same, and `RecordingSettingsMachine` can count it for A1's tests.
2. **`PanelApplyResult` and `applyFromPanel()` live in `LiveSettingsApplier.kt`** (package
   `app.thebox.core.catchup.archive`), not `ArchiveContracts.kt`: the interface is declared there. `applyFromPanel` is a
   default member, so `LiveSettingsApplier` stays a `fun interface` and the test lambdas compile. B0 appends there.
3. **`GuideStateMachine.keyContext`'s default already reads the flag**: GUIDE while `ui.value.settingsPanel`, PLAYER in
   FULL, else GUIDE. One rule, and fakes that publish the flag get the panel's table. A1 need not override it.
4. **The texts are complete in commit 0.** Thirteen append-only `SettingsText` keys (`PANEL_TIMING_REPREPARE`,
   `_CHANGE_CHANNEL`, `_NEXT_START`, `_RESTART_APP`, `_NEXT_UPDATE`, `_NEXT_GUIDE_UPDATE`, `PANEL_QUICK_BAR_HINT`,
   `PANEL_NOTICE_REPREPARING`, `_WAITS_FOR_RESUME`, `_CARD_SHOWN`, `PANEL_FULL_SCREEN_HINT`, `PANEL_ANNOUNCE_OPEN` "Settings,
   %1$s", `PANEL_ANNOUNCE_OPEN_ROOT` "Settings") with their §4.2 / §4.4 / §5.2 strings in `strings_settings.xml` and their
   lines in `ResourceSettingsTexts`. The exhaustive `when` there makes a key without a string a compile error, and it
   removes an A4 → A5 dependency (A5 reads the keys). A4 keeps `WpaPages.kt` and the builder tests, and may reword those
   thirteen strings. The root list has no page title, hence the `_ROOT` announcement.
5. **Two rule signatures beyond §2.6.** `SettingsPanelRules.timingText(PanelTiming): SettingsText?` (the §4.4 table,
   filled in commit 0: null for NONE and AT_ONCE; B2 changes it for stage B) and `quickBarHint(row): Boolean` (§4.1: the
   band adds `PANEL_QUICK_BAR_HINT` for `playback.aspect`, `playback.audio_language`, `playback.subtitles`; filled in 0b).
6. ~~Stub bodies~~ superseded by commit 0b (item 10): the rule bodies are real and frozen.
7. **`SettingsPanel(machine: SettingsMachine, lowEnd: Boolean, modifier: Modifier = Modifier)`**, in
   `ui/.../settings/SettingsPanel.kt`; commit 0 draws nothing. A5 owns the body and `object SettingsPanelUi`.
8. **Fakes.** `RecordingSettingsMachine`: `present` publishes `presentation` and is recorded (`presented`), `announces`,
   an ordered `log` ("present(PANEL)", "open(…)", "onLeave", "announceOpen"), `showRoute(route)`; `open` keeps the
   presentation. `FakeLiveSettingsApplier`: `panelApplies`, `panelResult` (not counted in `applied`).
   `FakeGuideStateMachine`: `keyContextOverride` (null = the default). `FakePlayerStateMachine`: `panelCalls`.
   `FakeSettingsUiMachine` (`:ui` tests): `present` publishes, `announces`.
9. **Neutral branches outside the contract files** (the panel never opens, so none runs): `GuideStateMachineImpl`
   `dispatchInner` `GuideEvent.ExpandSettings -> Unit` (A1); `PlayerStateMachineImpl.act` `PlayerLayer.SETTINGS_PANEL ->
   false` (A2); tests: `PlayerKeysTableTest.coverage` and `PlayerFiveKeyReachabilityTest.assertReach` skip SETTINGS_PANEL
   (entered by the guide, not by player keys; A2 adds its rows or reach), `ComposedFullBackLadderTest.setUp`
   `SETTINGS_PANEL -> return null` (A1/A2 add the ladder case).

### Commit 0b (2026-11-26, lead rulings on the A0 report)

10. **`SettingsPanelRules` bodies are frozen in commit 0b and owned by the lead.** A3 no longer owns the file; B2 edits it
    in stage B. `expands` is an exhaustive `when` over `SettingsRoute` (no `else`), exactly the §2.6 table;
    `SettingsPanelRulesTest` checks one or more instances of every route class (and fails when a route class has no
    row). `timing` maps `row.effect` one to one (§4.4), whatever the row kind. `quickBarHint` matches
    `PlaybackRows.ASPECT`, `AUDIO_LANGUAGE`, `SUBTITLES` by id or `"$id."` prefix (as S4).
11. **Menu and a long OK are one action.** `DefaultKeyMap` maps the Menu key and a long OK (both contexts) to
    `CONTEXT_MENU`; `expandKey` sees only the `BoxAction`, so it cannot tell them apart. The rule applied: CONTEXT_MENU
    expands only at `route == Root && pane == SECTIONS` (the root list, which has no row menus), never on a page, so a
    long OK on a row (S11) cannot expand. On the root list a long OK expands as Menu does; accepted. OPEN_SETTINGS
    expands at any route and pane (the editor never sees it: the router hands keys to Compose then).
12. **Table readings.** `FixGuide` with or without a channel, `SyncStatus`, `Channel(identity)`, `SaveLocation` (any
    purpose), `LocationDetail` and `PauseRewind` stay in the panel; `PhoneSetup` expands for every purpose (backup and
    log downloads included); `LocationTest` expands with either argument; `FirstRun` expands (never reached from FULL).
13. **Test ownership.** `PlayerKeysTableTest` and `PlayerFiveKeyReachabilityTest` belong to A2. The SETTINGS_PANEL case
    of `ComposedFullBackLadderTest` belongs to A1: the test runs the composed guide + player (`PlayerWorld(withGuide =
    true)`), and the panel needs the guide to open it with a settings machine attached to that world; it passes only
    once A2's layer exists, so A1 may leave the commit-0 skip until integration. A4 = `WpaPages.kt`, builder tests and
    rewording of the `PANEL_*` strings only. §11.1 lists the files per package after these rulings.
