# Player settings panel: design (proposal)

Status: **proposed** 2026-11-26 (proposer, Opus). Needs a reviewer, then the lead's sign-off.

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
`docs/design/m6-score-bug.md` ("SB §n") and `main` `f9c4a8a`.

**Scope:** a second **presentation** of the existing Settings (machine, tree, page sources, row views) over FULL. No
second settings machine, no second tree, no new page builders.

**Concurrent work:** another agent is changing `ui/.../full/PickerSheet.kt` and adding `ScoreSheet.kt`. This design
plans **no edit** to either. It needs **one line** in `ui/.../full/FullScreenHost.kt` (`scorePlacementInput`, §5.9),
which is likely to be touched by that work too (to draw the new sheet); WP-5 lands that line after it (§11).

---

## 0. Decisions at a glance

1. **One machine, two presentations.** `SettingsMachineImpl` gets a presentation flag
   (`SettingsPresentation.FULL_SCREEN | PANEL`). The panel is a new composable, `SettingsPanel`, that draws the same
   `SettingsUiState` in one column. The machine's two panes are already a usable one-column model: `Pane.SECTIONS` at
   Root is the panel's root list, `Pane.PAGE` is the page (§2.1).
2. **The guide machine owns the keys, mode stays FULL.** A new `GuideUiState.settingsPanel` flag. While it is set, the
   guide machine sends every key to `SettingsMachine.act` before the player machine, exactly as it does in mode
   SETTINGS. The player machine gets a passive layer, `PlayerLayer.SETTINGS_PANEL`, that only draws the quick bar with
   the Settings chip shown as active and lets four keys through (Stats, Play/Pause, Play, Pause) (§2.2).
3. **BACK ladder:** option list / overlay → page → parent page → … → root list → panel closes and the quick bar takes
   the keys again with focus on Settings and its 8 s auto-hide restarted. A panel opened from an error card closes back
   to the card (§2.4).
4. **PIN gate unchanged.** The same `openSettingsGated` path, the PIN pad over the video, then the panel (§2.5).
5. **Expand rule.** Pages drawn by another screen and the onboarding, phone and restore flows hand over to the normal
   full-screen Settings at the same path ("expand"). Card overlays (confirm, number pad, text editor, key capture) are
   drawn centred over the video as today; inline ones (option lists, context menu, move mode) stay in the panel. The
   Settings key anywhere, and Menu (☰) on the root list, also expand (§2.6, §2.7).
6. **Entry points:** quick bar Settings and the remote's Settings key in FULL open the panel; error-card Settings routes
   open the panel detached. Everything opened from the guide, Search, Sports or the groups panel stays full screen (§3).
7. **Live effect.** REPREPARE rows apply **at once** in the panel (400 ms after the change, one re-prepare of the
   current tune through the existing `LiveSettingsApplier`), not when Settings closes. NEXT_TUNE rows that change the
   picture or the connection **restart the current channel** to apply (a fresh cycle through the broker), only when the
   row's playlist is the one playing. Other timings keep their text (§4).
8. **"Decoder hand-off" and "Audio buffer" cannot show live today:** they are fixed when the one ExoPlayer is built
   (NEXT_LAUNCH). Making them REPREPARE is a separate, optional playback package (WP-6) behind a spike (§4.5).
9. **Layout:** the panel takes the picker sheet's box, (1376, 48) 496 × 880 design px, `PlayerUi.PanelFill` (canvas at
   .86), 1 px `Border09`, radius 24. Compact rows: value under the label, toggle/chevron at the right. The focused
   row's description moves to a fixed **description band** at the bottom of the panel (3 lines), which also carries the
   notice line (§5).
10. **Coexistence:** quick bar visible (no auto-hide), info bar suppressed, stats overlay kept (left side, no overlap),
    score bug avoids the panel through the picker box it already avoids, reminder card moves left as beside the picker,
    error card shown (centre, no overlap) and unfocused while the panel has the keys (§5.8–§5.13).
11. **Rules:** no blur, the panel is a sibling in the key sink (never an ancestor of `VideoLayer`), no infinite
    animation, fades snapped in low-end mode; the settings state is collected inside the panel only (§6).
12. **Not doing:** no preview pane, no live text size, no zapping from the panel, no panel from the guide, no new row in
    the sections list, no player rebuild (§7).

---

## 1. Facts (from the code at `f9c4a8a`)

| # | Fact | Where |
|---|---|---|
| F1 | Quick bar Settings: `PlayerStateMachineImpl.quickSelect` → `closeLayer(); effects.openSettings()` → `PlayerEffectsImpl.openSettings()` dispatches `GuideEvent.OpenSettings` → `openSettingsGated(SettingsRoute.Root)` | `PlayerStateMachineImpl.kt:838`, `PlayerEffectsImpl.kt:168`, `GuideStateMachineImpl.kt:416` |
| F2 | `openSettings` sets mode SETTINGS; `setMode` leaving FULL calls `effects.setFullScreen(false)` and `full.leaveFull()`, which closes every player layer and **turns the stats overlay off** | `GuideStateMachineImpl.kt:957–1005`, `PlayerStateMachineImpl.kt:349` |
| F3 | Mode SETTINGS is `HoleMode.COVERED`: the video keeps decoding to the parked surface but is hidden; `SettingsScreen` is an opaque canvas | `AppRoot.kt:95–130, 458` |
| F4 | The settings machine is UI-agnostic: `SettingsUiState` has `pane` (SECTIONS/PAGE), `sections`, `page`, `cursor`, `top` and every overlay. In SECTIONS at Root, `page` previews the focused section | `SettingsMachine.kt`, `SettingsMachineImpl.kt:984` |
| F5 | Keys in SECTIONS: UP/DOWN move, RIGHT/SELECT enter (Parental asks the PIN), BACK returns false (= leave). In PAGE: LEFT and BACK pop; at Root's page BACK goes to SECTIONS; at a detached route's bottom BACK returns false | `SettingsMachineImpl.kt:365–445, 766` |
| F6 | `SettingsMachine.act` returns false for OPEN_GUIDE and EXIT too (the guide leaves Settings / opens its exit dialog) | `SettingsMachineImpl.kt:169` |
| F7 | REPREPARE changes apply only when Settings closes: `onLeave` → `SettingsEffectsImpl.onClosed(timings)` → `LiveSettingsApplier.apply()` once | `SettingsMachineImpl.kt:194`, `SettingsEffectsImpl.kt:151` |
| F8 | `LiveSettingsApplierImpl.apply()`: an archive session re-requests at the playhead (`ArchivePlayback.reapply`); a LOCAL paused session re-prepares its reader and stays paused; else `PlaybackController.reapplySettings()` | `app/.../LiveSettingsApplierImpl.kt` |
| F9 | `reapplySettings()` = one new session of the current live cycle at the live edge, resolved URL reused, new decoders only when the video decoder mode changed; **no-op** when idle, stopped, VOD/archive, paused unbuffered, or while the error card shows | `PlaybackControllerImpl.kt:646–663` |
| F10 | `retry()` = a new cycle of the current target (fresh ladder, fresh re-resolve window); all connections go through the cycle's session and the broker | `PlaybackControllerImpl.kt:710` |
| F11 | `applySwitches` reads buffer, decoder, tunneling, audio output on every prepare; the AFR mode is collected live (`onAfrModeChanged`); `liveSpeedCorrection` is read when the media source is built | `PlaybackControllerImpl.kt:984, 1196–1243, 1315` |
| F12 | `codecQueueing` ("Decoder hand-off") and `audioBuffer` are passed to `BoxRenderersFactory` when `PlayerFactory` builds the player: fixed for the player's life (rows are NEXT_LAUNCH) | `PlayerFactory.kt:67`, `PlaybackRows.kt` |
| F13 | Per-channel memory wins over the global rows: `c.memory.bufferMode ?: s.bufferMode`; `channelDecoderOverride(c) ?: settings.videoDecoderMode` (the quick bar's Decoder chip) | `PlaybackControllerImpl.kt:1198, 1243` |
| F14 | Every row's `EffectTiming` is in the registry (`SettingsTree`); builders append the timing sentence to the description (`WpaPages.effectText`; WP-B's `PlaylistPages` has its own `SetupText.EFFECT_NEXT_TUNE`) | `tree/*Rows.kt`, `WpaPages.kt:844`, `PlaylistPages.kt:236–275` |
| F15 | Text size is read once per process (`UiTextScale.running`); the row is RESTART | `SettingsStore.kt:115–125`, `InterfaceRows.kt` |
| F16 | The picker sheet box is (1376, 48) 496 × 880, ending 24 px above the quick bar (y 952); the score bug already avoids it (`OverlayGeometry.picker`, `PlacementInput.picker`) | `PlayerUi.kt:87–92, 152`, `FullScreenHost.kt:272` |
| F17 | Error-card Settings routes open **detached** and PIN-free through `OpenSettingsDetached`; routes: `Playlist(id[, focus])`, `PlaylistSource(id)`, `NetworkTest(id)`; OPEN_EXTERNAL's fallback opens `Section(PLAYBACK)` detached | `ErrorCardModel.kt:81–94`, `ErrorRouteActionsImpl.kt:74, 104` |
| F18 | No Settings link exists from the score bug sheet today (`ui/.../full`, `ui/.../sports` reference no `SettingsRoute`) | grep |
| F19 | The PIN pad, toasts and the reminder card are drawn by `AppRoot` above every mode; the PIN prompt is modal in any mode | `AppRoot.kt:465–473`, `GuideStateMachineImpl.kt:478` |
| F20 | The text editor overlay owns Compose focus; the router hands keys to Compose while `GuideStateMachine.textInputActive`, which today requires mode SETTINGS. `AppRoot` re-focuses the key sink when `settings.ui.editor` goes null, in any mode | `GuideStateMachineImpl.kt:170`, `KeyRouterImpl.kt:161`, `AppRoot.kt:382–394` |
| F21 | The card overlays are centred cards on their own fill: `ConfirmCard` 720, `NumberPad` 560, `TextEditorOverlay` 1100 at y 120, `CaptureOverlay` 720 over a black .55 scrim; the context menu `SettingsMenu` is 420 wide | `SettingsUi.kt:97–100`, `CaptureOverlay.kt:62` |
| F22 | Two routes are drawn by other screens over the whole canvas: `PhoneSetup` (WP-D's phone page) and `ChannelEditor`; `FirstRun` is Welcome | `SettingsScreen.kt:92–112, 150` |
| F23 | Fixed widths that do not fit 496: the Test's `ProgressTrack` (1228), `VALUE_MAX_W` (560), the About wordmark (200 + 32) | `SettingsUi.kt:143`, `SettingsRowViews.kt:229, 239` |

---

## 2. Architecture

### 2.1 A presentation, not a fork

Contracts (commit 0, additive and defaulted):

```kotlin
// SettingsMachine.kt
enum class SettingsPresentation { FULL_SCREEN, PANEL }
data class SettingsUiState(/* …existing… */ val presentation: SettingsPresentation = SettingsPresentation.FULL_SCREEN)
interface SettingsMachine {
    /** Switches the presentation without touching the path, cursor or visit (S9 `onLeave` is not called). */
    fun present(p: SettingsPresentation) = Unit
}
// SettingsPages.kt
data class PageContext(val advanced: Boolean = true, val nowMs: Long, val panel: Boolean = false)
```

`SettingsMachineImpl` in PANEL:
- publishes `presentation = PANEL`;
- **skips the section preview**: in `Pane.SECTIONS` at Root `requestDisplayed()` does not load the focused section's page
  (the panel never draws it; this saves a page build per cursor move on a Fire TV Stick). Entering a section loads it;
- after every path change (`pushRoute`, `replace`, the push branch of `popTo`, `pushGated`'s step), if
  `SettingsPanelRules.expands(currentRoute())` it dispatches `GuideEvent.ExpandSettings` through `guideDispatch`, once
  per route (§2.6);
- `present(FULL_SCREEN)` during an open visit reloads the displayed page (`effects.loadPage`, the newer version
  replaces the cached one; no placeholder flash), so its texts become the full-screen ones (§4.4).

Everything else (key tables, overlays, cursor memory, paged lists, PIN steps, `onLeave`) is unchanged and shared.

`SettingsEffectsImpl.ctx()` sets `panel = machine.ui.value.presentation == PANEL`.

### 2.2 Who owns the keys

The guide machine already routes SETTINGS keys to the settings machine (`settingsKey`) and owns the PIN layer. The panel
keeps that: mode stays **FULL**, and a guide flag says the panel is open.

Contracts:
```kotlin
// GuideStateContracts.kt
data class GuideUiState(/* … */ val settingsPanel: Boolean = false)
sealed interface GuideEvent { /* … */ data object ExpandSettings : GuideEvent }
// PlayerContracts.kt
enum class PlayerLayer { /* … */ PLACE_SCORE, SETTINGS_PANEL } // append-only
data class QuickBarState(/* … */ val panelOpen: Boolean = false)
interface PlayerStateMachine { fun settingsPanel(open: Boolean) = Unit }
```

`GuideStateMachineImpl.act`, order (new step in bold):
1. `dropEntry`, the PIN prompt (modal), the reminder card (modal in FULL), as today.
2. **`if (u.mode == FULL && settingsPanel) { panelKey(a); return }`** before `overlayKey`, the player machine and
   `globalKey`.

`panelKey(a)`:
- `a.action in PASS_THROUGH` (`STATS_OVERLAY, PLAY_PAUSE, PLAY, PAUSE`) → `full.act(a)` (the player handles it in
  SETTINGS_PANEL, §2.3); the stats overlay can be switched on and off while editing.
- `SettingsPanelRules.expandKey(settings.ui.value, a.action)` → `expandPanel()` (§2.6).
- otherwise `settings.act(a)`; when it returns **false**: BACK → `closePanel()`; OPEN_GUIDE → `closePanel()` then
  `returnToGuide()` (enters the guide from FULL as today); EXIT → `closePanel()` then `exitStep()`.

Also: `textInputActive` becomes `(mode == SETTINGS || settingsPanel) && settings?.textInputActive == true`;
`horizontalRepeat` is false while `settingsPanel` (a held ← must not pop several pages); `setMode(m != FULL)` closes
an open panel first (`closePanel()`), except in `expandPanel()`.

The player machine never sees panel keys except PASS_THROUGH. In `PlayerLayer.SETTINGS_PANEL` it:
- publishes `quick = quickState().copy(focused = index of SETTINGS, panelOpen = true)`; **no** `quickHideAt`;
- shows no passive info bar (`showPassive` is skipped; `zap` never runs because keys do not reach it, but a reminder
  "Switch now" or a failover tune can change the channel: the quick bar's values refresh as in QUICK_BAR, and
  `FavPurpose.QUICK` answers are accepted in SETTINGS_PANEL too);
- does not dim a paused frame (`dimmed()` is false in SETTINGS_PANEL);
- keeps the stats overlay as it was;
- on an error (`syncError`) keeps SETTINGS_PANEL: the card is published with `focused = -1` (no button ringed) and
  takes the keys when the panel closes;
- `act` handles PASS_THROUGH only (as on bare video); anything else returns false.

`settingsPanel(open = true)` remembers the layer it came from (`QUICK_BAR` or `ERROR`), closes the picker, list, scrub
and place mode (`openLayer` rules), and sets SETTINGS_PANEL. `settingsPanel(false)` returns to that layer: QUICK_BAR
with `quickFocus = SETTINGS` and `quickHideAt = now + QUICK_BAR_IDLE_MS`, or ERROR (`base()`) when the error is still
active.

`quickSelect(SETTINGS)` no longer calls `closeLayer()` first: the quick bar stays while the PIN prompt shows and the
panel opens on top of it. If the guide opens full-screen Settings instead (no settings machine attached, §2.3), the
mode change closes the layer as today.

### 2.3 Open and close

```kotlin
// GuideStateMachineImpl
private fun openSettings(route: SettingsRoute = Root, detached: Boolean = false) {
    if (u.mode == ScreenMode.FULL && settings != null && full != null && !SettingsPanelRules.expands(route)) {
        openPanel(route, detached); return
    }
    /* existing body */
}
private fun openPanel(route, detached) {
    endBoot(); u = u.copy(overlay = null, settingsPanel = true); settingsPanel = true; panelDetached = detached
    settings!!.present(PANEL)            // before open: the first page is built with ctx.panel
    settings!!.open(route, fromFull = true, detached = detached)
    full!!.settingsPanel(true)
}
private fun closePanel() {
    settingsPanel = false; u = u.copy(settingsPanel = false)
    settings?.onLeave()                  // S9: once per visit; applies what is still pending (§4.3)
    settings?.present(FULL_SCREEN)       // after onLeave: nothing to reload
    full?.settingsPanel(false)
}
private fun expandPanel() {              // GuideEvent.ExpandSettings, or an expand key
    settingsPanel = false; u = u.copy(settingsPanel = false)
    settingsRoute = settings!!.ui.value.route; settingsDetached = panelDetached
    setMode(ScreenMode.SETTINGS)         // leaves FULL as today (F2); no onLeave: the visit continues
    settingsFromFull = true
    settings!!.present(FULL_SCREEN)      // same path, same cursor
}
```

- The quick bar (`OpenSettings`) and the Settings key in FULL (`globalKey` OPEN_SETTINGS) both go through
  `openSettingsGated(Root)` → `openSettings`, so both open the panel (open question Q2).
- The PIN grant (`PinPurpose.EnterSettings`) calls `openSettings(route)` while the mode is still FULL: the panel.
- `OpenSettingsDetached(route)` (error cards) → `openSettings(route, detached = true)` → panel, detached (Q3).
- No settings machine attached yet (S11) → the existing full-screen path, unchanged.
- After `expandPanel()`, BACK at Root (or at a detached route's bottom) returns to **FULL** (`settingsFromFull`), not to
  the panel. Simple and the same as today's full-screen Settings from the quick bar.

### 2.4 BACK at each depth

| Where the focus is | BACK | LEFT |
|---|---|---|
| Text editor | M5 S3 ladder (chips → field → Cancel) | Compose |
| Key capture, number pad, confirm card, context menu, move mode | closes it (M5 §3.2.4, unchanged) | as today |
| Inline option list | closes it (MultiChoice commits) | same |
| A page deeper than a section page | the parent page, cursor restored | same |
| A section page | the root list, cursor on that section | same |
| The root list (`Pane.SECTIONS`) | **panel closes**; quick bar with focus on Settings, 8 s auto-hide | nothing |
| A detached route's bottom page (error card, OPEN_EXTERNAL fallback) | **panel closes**; back to the error card (focused) or to the quick bar | same as BACK |

The Guide key closes the panel and enters the guide; Exit closes it and runs the exit step; the Settings key and Menu on
the root list expand (§2.6).

### 2.5 PIN gate

Unchanged. "Lock settings" with a PIN set and the session locked: Settings chip → PIN pad (drawn by `AppRoot` over the
video, F19) → on success the panel opens. Gated rows and links inside (`needsPin`, `pushGated`, the Parental section,
menu options with `needsPin`) ask through the same `PinGate` with `PinPurpose.SettingsAction`, over the video. Detached
error-card routes stay PIN-free for the route itself and ask for links into gated routes (M5 S2). The score bug hides
under the PIN pad as today (`ScoreBugHostFacts.modal`).

### 2.6 What expands, and the route table

New `core/src/main/kotlin/app/thebox/core/settings/state/SettingsPanelRules.kt`:

```kotlin
object SettingsPanelRules {
    /** Routes the panel hands to full-screen Settings. An exhaustive `when` (no else) so a new route must be classified. */
    fun expands(route: SettingsRoute): Boolean
    /** Settings key anywhere; Menu on the root list, or on a row without a context menu. */
    fun expandKey(ui: SettingsUiState, action: BoxAction): Boolean
    val RETUNE_ROWS: Set<RowId>          // §4.2
    val ARCHIVE_RETUNE_ROWS: Set<RowId>  // §4.2
    const val APPLY_DEBOUNCE_MS = 400L
}
```

**Rule:** a route expands when (a) another screen draws it over the whole canvas, (b) it is part of the onboarding or
import flow, or (c) it replaces the library or plays nothing for the user to watch (restore). Everything else is a list
of rows and stays in the panel.

| Route | Panel / Expand | Why |
|---|---|---|
| `Root` | Panel (the root list = `Pane.SECTIONS`) | – |
| `FirstRun` | Expand (never reached from FULL) | Welcome cards |
| `Section(PLAYLISTS)` | Panel | the mock-up's page |
| `Section(GUIDE / PLAYBACK / CHANNELS / INTERFACE / SPORTS / REMOTE / PARENTAL / BACKUP / NETWORK / ABOUT)` | Panel | rows only |
| `AddSource` | **Expand** | forms of the setup wizard (b) |
| `PhoneSetup(any purpose)` | **Expand** | WP-D's full-canvas page with the QR code (a) |
| `ImportProgress`, `GroupsReview`, `MatchingReport` | **Expand** | import flow, "Start watching" (b) |
| `Playlist(id[, focus])` | Panel | account card + rows; error-card target |
| `PlaylistSource(id)` | Panel | Text rows; the editor is centred (§2.7). A save that pushes `ImportProgress` expands then |
| `NetworkTest(id)` | Panel | Info lines and actions |
| `Groups`, `HiddenChannels`, `HideRules` | Panel | Item lists; move mode is one-column already |
| `FavouriteLists`, `FavouriteList` | Panel | – |
| `EpgSources`, `SyncStatus`, `FixGuide(null / channel)` | Panel | lists |
| `Channel(identity)` | Panel | not reachable from the panel today (guide menu only) |
| `ChannelEditor` | **Expand** | its own full-canvas screen (a) |
| `ButtonMapping`, `ActionBindings` | Panel | the capture card is centred (§2.7) |
| `Diagnostics`, `Licences` | Panel | Info rows (Licences is long but scrolls) |
| `Backup` | Panel | its phone links expand when chosen |
| `RestorePreview(token)` | **Expand** | replaces settings and playlists, "Start watching" (c) |
| `PauseRewind`, `SaveLocation`, `LocationDetail`, `FindComputers`, `ComputerShares`, `ComputerSignIn`, `LocationSignIn`, `NetworkFolder` | Panel | rows, Text rows, the folder walk |
| `LocationTest` | Panel | the progress track fills the row's width in compact rows (F23, §5.4) |

`ExpandSettings` arrives through the guide's `dispatch` and runs `expandPanel()`. A route that expands is never opened
in the panel in the first place (`openSettings` checks it).

### 2.7 Overlays and row kinds in the panel

| Thing | Treatment |
|---|---|
| `RowKind.Toggle` | compact row, pill at the right (`TogglePill`, unchanged) |
| `RowKind.Choice` | value under the label; OK opens the inline option list under the row (indent 24, §5.5) |
| `RowKind.MultiChoice` | as Choice, check boxes; value = the selected labels joined, ellipsised |
| `RowKind.Text` | value under the label (masked as dots); OK opens `TextEditorOverlay` **centred over the video** as today (1100 × at y 120; it overlaps the panel while open, which is fine: it is modal). The IME covers the lower half; the video stays visible above |
| `RowKind.Number` | value + unit under the label; OK or a digit opens `NumberPad` centred (560) |
| `RowKind.Action` | label; destructive dot at the right; `confirm` → `ConfirmCard` centred (720) |
| `RowKind.Link` | label, value under it when set, chevron at the right; opens in the panel or expands (§2.6) |
| `RowKind.Info` | label + up to 3 lines under it (storage rows: one line each, all shown, the row grows) |
| `RowKind.Item` | label, value under it, badges on the value line, chevron when it has a route; `menu` → `SettingsMenu` inside the panel (420 wide, right-aligned, anchored to the row as `menuTop` but clamped to the panel's rows area) |
| Move mode | `MoveModeRow` in the one column (no change) |
| Key capture | `CaptureOverlay` centred with its .55 scrim (the video dims for the few seconds of a capture) |
| `PageModel.placeholder` | the placeholder text in the rows area, 22 px, up to 3 lines |
| `PageModel.subtitle` | under the page title, 22 px, up to 2 lines |
| Paged lists (`PagedList`) | unchanged (`PlaceholderRow` for unloaded rows, 64 px) |
| About header (`AboutRows.HEADER`) | compact: no wordmark, the Info lines only (F23) |
| The Test's progress row (`StoragePages.ROW_T_PROGRESS`) | `ProgressTrack` takes the row's width in compact rows (new `width` parameter, default 1228) |
| Searching dots (`ROW_SEARCHING`) | unchanged at the right |
| Sports interval row (6-line focused detail) | its detail goes to the description band like any description, cut at 3 lines; the full text is in full-screen Settings (Settings key) |
| `playback.restart_now`, `interface.restart_now`, `about.reset` | as today (they restart the app) |

---

## 3. Entry points

| From | Today | With this design |
|---|---|---|
| Quick bar → Settings (FULL) | full-screen Root, BACK → FULL | **panel**, Root, PIN-gated |
| Remote Settings key (`OPEN_SETTINGS`) in FULL | full-screen Root | **panel** (Q2) |
| Error card OPEN_SETTINGS (`Playlist`, `PlaylistSource`, `NetworkTest`) | full-screen, detached, BACK → FULL card | **panel, detached**, BACK → the card (Q3) |
| Error card OPEN_EXTERNAL fallback (`Section(PLAYBACK)`, detached) | full-screen | **panel, detached** |
| Score bug sheet | no Settings link (F18) | none added. If the new `ScoreSheet` gains one, it calls `PlayerEffects.openSettings(Section(SPORTS))` and gets the panel by the same rule |
| Guide gear, Groups shortcut, guide context menu (Channel options, Fix guide data, group Edit), sync chip, empty-state "Add one", first run | full screen | **unchanged** (the mode is not FULL) |
| From the panel to full screen | – | Settings key anywhere; Menu (☰) on the root list or on a row without a context menu; any expand route (§2.6). The root header shows the hint "☰ Full screen" |

---

## 4. Live effect

### 4.1 Today, per timing (F7–F15)

| Timing | Rows (registry) | What happens today | In the panel |
|---|---|---|---|
| LIVE | AFR; reconnect attempts, failover, after standby, HDR preview, info bar, number wait; interface rows except text size; remote rows; network rows; guide interval/hour/shift/posters; Sports (show, corner, delay, interval, size, style); parental; channel lists | applied when the store write lands (AFR: collected by the controller; the score bug re-reads `SportsPrefs`) | **visible at once** where it is visible in the player: AFR (display mode switch), the whole score bug group, the clock. Reconnect/failover/standby act on the next such event. Guide-only rows (posters, logos, numbers, ambient, mini player) show when the guide is next shown |
| REPREPARE | `playback.buffer`, `audio_output`, `decoder`, `tunneling`, `live_speed`; `channel.buffer` | once, when Settings closes (`onClosed`) | **applied 400 ms after the change** (§4.2) |
| NEXT_TUNE | `playback.aspect`, `audio_language`, `subtitles`, `external_player`; `playback.timeshift`, `timeshift_location`; `playlist.user_agent`, `headers`, `stream_format`, `backup_servers`, `catchup_mode/days/zone`, `padding_before/after`, `max_connections`, `ignore_tls`; `channel.aspect/decoder/tunneling/afr` | at the next tune | the rows in `RETUNE_ROWS` **restart the current channel** (§4.2); the others keep "Takes effect at the next channel change" |
| NEXT_LAUNCH | `playback.codec_queueing` ("Decoder hand-off"), `audio_buffer`, `on_launch`; `playlist.update_on_start`; `channels.open_guide_on` | next process start | unchanged text. WP-6 proposes REPREPARE for the first two (§4.5) |
| RESTART | `playback.ffmpeg`, `interface.text_size`, `about.reset` | next start, or "Restart now" | unchanged ("Restart now" restarts the app; with "On launch: full screen, last channel" the channel comes back) |
| NEXT_REFRESH / NEXT_EPG_IMPORT | playlist source, numbering, order; guide sources, matching, days, descriptions | next refresh / import | unchanged |
| NONE | links and actions | – | – |

### 4.2 Panel rules

In `SettingsEffectsImpl.onIntent`, after `runIntent` (the store write is in memory by then) and only while
`presentation == PANEL`:

- the row's effect is REPREPARE (registry spec; WP-B's page-local sub-rows carry their parent's id prefix and effect,
  as `EffectTimingCoverageTest` checks) → `scheduleApply(retune = false)`;
- the row is in `RETUNE_ROWS` → `scheduleApply(retune = true, playlistId)`;
- the row is in `ARCHIVE_RETUNE_ROWS` → `scheduleApply(retune = true, playlistId, archiveOnly = true)`.

`scheduleApply` cancels the pending job and launches a new one on `main` after `APPLY_DEBOUNCE_MS` (400 ms; two quick
toggles give one re-prepare; a retune request wins over a re-prepare in the same window). It calls:

```kotlin
// ArchiveContracts.kt (commit 0, additive)
enum class PanelApplyResult { APPLIED, RESTARTED, WAITS_FOR_RESUME, NOTHING_PLAYING, OTHER_PLAYLIST }
interface LiveSettingsApplier {
    fun apply()
    fun applyFromPanel(retune: Boolean, playlistId: Long?, archiveOnly: Boolean = false): PanelApplyResult {
        apply(); return PanelApplyResult.APPLIED
    }
}
```

`LiveSettingsApplierImpl.applyFromPanel`:
1. Nothing tuned (`PlaybackState.Idle`, stopped) → NOTHING_PLAYING.
2. `playlistId != null` and the playing tune's `target.identity.playlistId` differs → OTHER_PLAYLIST.
3. `archiveOnly` and no archive session → NOTHING_PLAYING (the row applies to the next catch-up request anyway).
4. An archive session (PLAYING … PROBING) → `archive.reapply(playhead)` (a re-request: new URL, headers and format) →
   APPLIED. Paused archive → WAITS_FOR_RESUME (today's rule: leaving Settings must not start playback).
5. The error card is up (`PlaybackState.Error` / the controller's `cardShown`) → `controller.retry()` → RESTARTED. This
   is the "did that fix it?" case: `reapplySettings()` is a no-op there (F9).
6. `retune` → `controller.retry()` (a new cycle; it resolves again, so user agent, headers, stream format, TLS and
   backup servers take effect; the broker cancels the old call before the new one) → RESTARTED. A LOCAL time-shift
   buffer starts again (the row's panel text says so).
7. Otherwise `apply()` (today's path) → APPLIED, or WAITS_FOR_RESUME for a paused unbuffered live tune.

The machine shows the result on the notice line (`SettingsEvent.Notice`, 3 s): APPLIED "Applied to this channel",
RESTARTED "Restarting the channel to apply…", WAITS_FOR_RESUME "Applies when playback resumes", OTHER_PLAYLIST
"Applies to channels of this playlist", NOTHING_PLAYING nothing.

`RETUNE_ROWS` = `playback.audio_language`, `playback.subtitles`, `playback.timeshift`, `playback.timeshift_location`,
`playlist.user_agent`, `playlist.headers`, `playlist.stream_format`, `playlist.backup_servers`, `playlist.ignore_tls`,
`playlist.max_connections`, `channel.decoder`, `channel.tunneling`, `channel.afr` (the `channel.*` rows only when the
route's channel is the playing one). `ARCHIVE_RETUNE_ROWS` = `playlist.catchup_mode`, `catchup_days`, `catchup_zone`,
`padding_before`, `padding_after`. Left out on purpose: `playback.aspect` (the quick bar's Aspect chip changes the
playing channel at once; the row is the default for others), `playback.external_player`. A test checks both sets are
NEXT_TUNE rows of the registry.

`onClosed` must not re-prepare a second time: the effects keep `skipNextReapply`, set when a panel apply ran and
cleared when a REPREPARE intent runs in FULL_SCREEN (after an expand). `onClosed` applies only when
`REPREPARE in timings && !skipNextReapply`, then resets it. A pending debounced apply runs at once on `onClosed`.

Stream connections: nothing here opens a connection. `reapplySettings`, `retry` and `archive.reapply` all go through
the cycle's session and `StreamConnectionBroker` as today.

### 4.3 Debounce and stacking

- One re-prepare per 400 ms window at most. A change during a re-prepare in flight schedules one more (the controller's
  newest session wins; `launchSession` replaces the previous one).
- The panel never applies on cursor moves or on opening an option list, only on a committed intent.

### 4.4 Texts

Builders read `ctx.panel` (§2.1) and swap two timing sentences; the effect values do not change
(`EffectTimingCoverageTest` stays as is):
- REPREPARE: "Takes effect when you leave Settings." → `EFFECT_REPREPARE_PANEL` "Applies right away. The picture may
  pause for a moment."
- NEXT_TUNE rows in `RETUNE_ROWS`: → `EFFECT_RETUNE_PANEL` "Restarts this channel to apply. Rewind starts again."
  (`ARCHIVE_RETUNE_ROWS`: "Applies to catch-up now, and at the next channel change.")

Files: `WpaPages.effectText(id)` (WP-A rows) and `PlaylistPages` (WP-B's five NEXT_TUNE descriptions use
`SetupText.EFFECT_NEXT_TUNE`). `ChannelOptionsPage` is not reachable from the panel and keeps its text.

### 4.5 "Decoder hand-off" and "Audio buffer" (WP-6, optional, separate review)

The owner's first example cannot work live today (F12). Both are set on `BoxRenderersFactory` when the one ExoPlayer is
built, and CLAUDE.md forbids a second player. Proposal for a separate Opus playback package, after a spike:
- `codecQueueing`: `BoxRenderersFactory.getCodecAdapterFactory()` returns a factory that reads the **current** mode in
  `createAdapter` (async or sync `DefaultMediaCodecAdapter.Factory` per codec). New codecs pick it up; `reapplySettings`
  asks for `freshDecoders` when the mode changed (as it does for the video decoder mode, F9). The row becomes
  REPREPARE.
- `audioBuffer`: an `AudioTrackBufferSizeProvider` that reads the current size whenever the sink creates an
  `AudioTrack`; it applies when the AudioTrack is next re-created. Whether a re-prepare re-creates it on every device is
  **unverified** (spike item S1).
Until WP-6 lands, both rows keep "Takes effect the next time The Box starts."

---

## 5. Layout

All numbers are design px (1 px = screen width / 1920), `BoxDesignScale`. Text through `GuideUi.sans/mono`, which scale
with `UiTextScale` (Small .9, Default 1, Large 1.1, Largest 1.2). New `object SettingsPanelUi` in `SettingsPanel.kt`.

### 5.1 Panel box

| Item | Value |
|---|---|
| Box | x 1376, y 48, w 496, h 880 (= `PlayerUi.PICKER_*`; right edge 1872, bottom 928, 24 above the quick bar) |
| Fill / border / radius | `PlayerUi.PanelFill` (canvas .86), 1 px `PlayerUi.Border09`, `DesignTokens.Radii.Panel` (24); no blur |
| Padding | 16 left/right, 20 top, 16 bottom; inner width 464 (x 1392…1856) |
| Clip | the panel clips its rows to its shape (as `PagePane` does); it is a sibling in the key sink, never an ancestor of `VideoLayer` |
| Show / hide | 150 ms fade on the panel's own content (`PlayerUi.FADE_MS`), snapped when `lowEnd` (low-end mode or Reduce animations), like the picker |

### 5.2 Header (breadcrumb)

| Item | Default | Largest |
|---|---|---|
| Row | y 68, height 40 (grows with text) | ~48 |
| "←" | 26/600 Fg, at inner x 12 | 31 |
| Parent title | 22/500 Fg55, max 160 wide, ellipsis. Root's title is "Settings" (`settings_ui_title`) | 26 |
| Divider | 1 × 24, White .22, 12 px each side | – |
| Current title | 24/600 Fg, fills the rest, ellipsis | 29 |
| At Root | "Settings" 24/600 alone (no arrow, no divider); at the right "☰ Full screen" 22/400 Fg55 | – |
| Hairline | 12 below the header row, 1 px White .12, inner width | – |

The left part is the **parent** page's title (Root → "Settings"), so it names where BACK goes. At a section page this
reads "← Settings | Playlists", as in the mock-up. A detached route's bottom page shows its own title only.

### 5.3 Page title

Under the hairline +16: `page.title` 28/600 Fg, one line (as the mock-up's "Playlists"); `page.subtitle` 22/400 Fg60,
up to 2 lines, 4 below. Not drawn at Root. Rows start 12 below: y 184 without a subtitle (panel-relative 136), y 132 at
Root.

### 5.4 Compact rows (`SettingsRowView(…, compact = true)`)

| Item | Default | Largest |
|---|---|---|
| Width | 464 (inner width) | same |
| Min height | 64 one line; 92 with a second line; rows grow with their text (`heightIn`) | ~64 / ~100 |
| Gap | 6 | 6 |
| Padding | 16 horizontal, 10 vertical | same |
| Radius | 12 (`Radii.Row`, as the picker rows) | – |
| Label | 26/500 Fg92 (focused: canvas), up to 2 lines, ellipsis | 31 |
| Second line | the value (`valueText`) 22/400 Fg65, mono 22 for mono values, 1 line, `MiddleEllipsis` for URLs/hosts; badges (`BadgeChip`, `AccentBadgeChip`) lead the line; Info rows: their lines here, up to 3 (storage rows: all) | 26 |
| Trailing | toggle pill 56 × 30; chevron "›" 26 Fg45; destructive dot 10; 12 px from the label | – |
| Focus | FG fill, 3 px accent ring (`SettingsUi.FOCUS_RING`), dark text; secondary text via `SettingsUi.secondary` | – |
| Disabled | alpha .4; the disabled reason shows in the band | – |
| Not drawn inline | the description (it goes to the band, §5.6) | – |

Toggle rows have no second line (the pill is the value), unless their `value` is set. Choice rows show the selected
label as the second line. A Link row with no value is one line.

### 5.5 Inline option list

Under its row: indent 24, option rows 56 (76 with a `detail`, which goes on a second line 22 Fg55, not beside the
label), radio 18 or check box 18, 14 px gap, label 24/400. Focus as rows. `bringIntoView` keeps the focused option
visible, as today.

### 5.6 Description band (and notice)

| Item | Default | Largest |
|---|---|---|
| Position | bottom of the inner box: y 800…912; hairline at its top (1 px White .12) | y ~782…912 |
| Height | 12 + 3 × 29 + 12 ≈ 112 (`BAND_LINES = 3`, line height 1.3 × 22) | 12 + 3 × 35 + 12 ≈ 130 |
| Text | the focused row's `secondLine(row)` (description, or the disabled reason), 22/400 Fg70, up to 3 lines, ellipsis. Root: the focused section's one-line summary is not available today, so the band is empty there | 26 |
| Notice | `ui.notice` replaces the text for 3 s: 22/500 Fg92 with a 4 × 22 accent bar at the left | – |

The band keeps its height when empty, so rows never jump. The rows area runs from the rows' top to 8 above the band:
608 px at Default (about 8 one-line rows or 6 two-line rows), about 575 at Largest.

### 5.7 Scrolling

Unchanged model: the machine's `top` with `VISIBLE_ROWS = 8`, and `revealCursor` scrolls further when the cursor row
does not fit (variable heights). `LazyColumn(userScrollEnabled = false)`, keys from `SettingsRowKeys`. No scroll bar
(as full-screen Settings).

### 5.8 Quick bar

Visible the whole time the panel is open, no auto-hide (§2.2). The Settings pill is drawn **active, not focused**
(`QuickBarState.panelOpen`): fill White .16, 2 px accent border, FG text (focused pills are FG-filled with dark text, so
the two never look alike). `QuickBar.kt` only; the row still scrolls so the Settings pill is visible. On close the bar
takes the keys with focus on Settings (normal focused style) and hides after 8 s.

### 5.9 Score bug

`scorePlacementInput` (`FullScreenHost.kt`) sets `picker = ui.picker != null || state.settingsPanel`: the panel's box is
the picker's box (F16), so `ScoreBugPlacement` already moves the bug out of it. No `:core` sports change, no new
`OverlayGeometry` field. (If the concurrent score-sheet work adds its own box, the panel keeps using `picker`.) The
Sports rows are LIVE, so size, style and corner changes show on the bug while the panel is open; a top-right corner is
avoided while the panel is up and returns when it closes.

### 5.10 Stats overlay ("Stream info")

Kept: FULL never left, so `leaveFull` does not switch it off (F2). It sits at (64, 56), at most 600 × 508; the panel
starts at x 1376: no overlap. The Stats key (PASS_THROUGH) toggles it while the panel has the keys; the quick bar's
Stream info pill shows ✓ when on. This is the recommended way to judge a decoder or buffer change.

### 5.11 Info bar, toasts, reminder card

- Info bar and scrub bar: not shown while the panel is open (the quick bar is the bottom bar).
- Toasts: bottom-left, lifted over the quick bar (`TOAST_B_OVER_QUICK`, 156), as today; no overlap with the panel.
- Reminder card: `reminderEndFor(shown, pickerOpen || ui.settingsPanel)`: right offset 568, beside the panel. It stays
  modal in FULL (it takes keys first, F19); "Switch now" tunes and the panel stays open.

### 5.12 Error card, playback failure, channel change

- Playback fails while the panel is open: the card appears centred (x 600…1320, no overlap with the panel), unfocused
  (`focused = -1`); the panel keeps the keys. A playback change made now applies with `retry()` (§4.2 step 5): if it
  fixes the stream the card goes away. BACK out of the panel focuses the card.
- Opened from the card (detached route): the same, and BACK at the route's bottom returns to the card.
- The channel changes while open (reminder "Switch now", failover, a PIN-granted tune): the panel stays on its page;
  the quick bar's values refresh; later applies target the new channel (`OTHER_PLAYLIST` when a playlist page no longer
  matches).
- Paused frame: not dimmed while the panel is open (§2.2); the paused card is drawn under the panel.
- Subtitles: unchanged (lifted over the quick bar); a long line can pass under the panel's left edge.
- Catch-up probing line (top centre, x 600…1320) and the stopped hint (centre): no overlap.
- Number entry, group card, exit hint: cannot open while the panel has the keys; an open one is closed when the panel
  opens (`overlay = null`).

### 5.13 Where it is drawn

`AppRoot`, inside the key sink, right after `FullScreenHost` when `shown == FULL && ui.settingsPanel &&
settingsMachine != null`: `SettingsPanel(settingsMachine, lowEnd)`. It is therefore above the player overlays and below
the reminder card, the PIN pad and the toast. `SettingsPanel` draws, in this order: the panel box (header, title, rows,
band, the context menu), then the centred card overlays (`ConfirmCard`, `NumberPad`, `TextEditorOverlay` wired exactly
as `SettingsScreen` wires it, `CaptureOverlay`) over the whole screen.

---

## 6. Performance and rules

- No blur; translucent fills only. No `graphicsLayer`, alpha or clip on an ancestor of `VideoLayer`: the panel is a child
  of the key sink, after `FullScreenHost` (`RootTreeRuleTest` gets a FULL + panel case).
- The video keeps the FULL hole and viewport: opening the panel changes no viewport and toggles no track (unlike
  full-screen Settings, F3).
- Recomposition: `SettingsPanel` collects `machine.ui` itself; `AppRoot` reads only `GuideUiState.settingsPanel`
  (already collected). The player's state does not change while the panel is open (no quick-bar timer, no info bar),
  so `FullScreenHost` does not recompose per settings key. Rows are keyed by `RowId`.
- Fire TV Stick: at most ~10 compact rows composed; no per-frame work, no infinite transition; the section preview
  build is skipped (§2.1); page builds stay on `Dispatchers.Default`; the page cache is unchanged (3 + path). Each
  REPREPARE costs one re-prepare (a short rebuffer), by design; the 400 ms debounce bounds it.
- TalkBack/VoiceView: the panel root has `paneTitle` "Settings"; rows keep `rowDescription` (label, second line, Info
  lines, value) and `selected`; the machine's row announcements reach `AppRoot` through `SettingsShellEvent.Announce` in
  any mode; the active Settings pill has `stateDescription` "Open"; apply results are notices, which the machine
  announces.
- Text ≥ 22 design px everywhere (band 22, second line 22, labels 26).
- Logs: the applier logs `reapply from panel: <result>` without URLs or row values (masked rows never print, U-L10).

---

## 7. What this design chose not to do

- No preview pane and no second column: the root list is the sections list; a section opens in place.
- No new settings machine, tree or page builders; no copy of the key tables.
- No live text size: the guide caches measured widths per process (F15).
- No player rebuild for NEXT_LAUNCH rows; WP-6 is the only playback change proposed, and it is optional.
- No zapping from the panel: CH± pages the list, as in full-screen Settings (Q4).
- No panel from the guide, Search or Sports, and no "return to the panel" after an expand.
- No extra row in the sections list for "full screen" (the sections are an enum; a key and a hint are enough).
- No change to the score bug placement code or `OverlayGeometry`.
- No per-channel override hint on the global Playback rows (F13), see R3.

---

## 8. Risks

| # | Risk | Handling |
|---|---|---|
| R1 | A retune drops the LOCAL rewind buffer and costs a reconnect (2–5 s) | only `RETUNE_ROWS`, only the playing playlist, stated in the row text and the notice; Q1 |
| R2 | A re-prepare on every toggle on a slow box | 400 ms debounce, one in flight; same path as leaving Settings today |
| R3 | The global Video decoder / Buffer row seems to "do nothing" because the channel has its own override (quick bar Decoder chip, per-channel buffer memory, F13) | the quick bar shows "Decoder · <value>" next to the panel; a follow-up can add "This channel uses its own decoder" to the row's band text |
| R4 | Keys reaching the wrong machine (player vs settings) | one routing point in the guide (`panelKey`) before the player; tests cover every key class (§10) |
| R5 | Async navigation from page sources into expand routes | the machine dispatches `ExpandSettings` on every path change, not the guide on keys |
| R6 | The text editor's Compose focus while mode is FULL | `textInputActive` covers the panel; `AppRoot` already re-focuses the sink on editor close in any mode (F20) |
| R7 | Merge conflict with the score-sheet work in `FullScreenHost.kt` | one line, landed after that work (WP-5 last) |
| R8 | Double re-prepare on close | `skipNextReapply` (§4.2), test |
| R9 | `retry()` during an archive session behaves differently from a live one | archive sessions use `archive.reapply`, never `retry` (§4.2 step 4) |

---

## 9. Open questions for the owner (with the default work proceeds on)

| # | Question | Default |
|---|---|---|
| Q1 | When you change a setting that needs the channel restarted (stream format, user agent, audio language, rewind on/off…), should the panel restart the channel by itself, or wait for you to press a "Restart channel" row? | **Restart by itself**, with "Restarting the channel to apply…" (your goal: see the effect without going back and forth) |
| Q2 | Should the remote's Settings key while watching also open the small panel? | **Yes**, same as the Settings chip |
| Q3 | When an error card's "Settings" button opens a page (e.g. Stream format), should that also be the small panel beside the card? | **Yes**: change it, see if the card goes away, BACK returns to the card |
| Q4 | In the panel, CH+/CH− page through the list (as in full Settings). Would you rather they change channel so you can compare? | **Page the list** (consistent with full Settings) |
| Q5 | When you reopen the panel, start at the root list or at the page you last had open? | **Root list**, focused on the section you last used (today's behaviour); a "reopen where I was" is a small follow-up |

---

## 10. Tests

`:core` (JVM):
- `GuideSettingsPanelTest`: quick bar Settings in FULL → `settingsPanel`, mode FULL, `settings.present(PANEL)` then
  `open(Root, fromFull, false)`, player told; from GUIDE → mode SETTINGS (unchanged); "Lock settings" → PIN →
  panel; Settings key in FULL → panel; `OpenSettingsDetached` in FULL → panel detached; BACK at root → closed, `onLeave`
  once, player `settingsPanel(false)`; OPEN_GUIDE → closed + guide; EXIT → closed + exit step; PASS_THROUGH keys reach
  the player; `ExpandSettings` → mode SETTINGS, same route, no `onLeave`, BACK → FULL; expand key at Root;
  `textInputActive` with the editor open in the panel; `horizontalRepeat` false; no settings machine → full screen;
  `setMode` away closes the panel; a reminder card still takes keys first.
- `PlayerSettingsPanelLayerTest`: SETTINGS_PANEL publishes `quick` with `panelOpen` and focus on SETTINGS; no
  auto-hide after 8 s; no passive info bar on a Tuned of a new channel; an error keeps the layer and publishes the card
  with `focused = -1`; close → QUICK_BAR focus SETTINGS and an 8 s timer, or ERROR; Stats key toggles stats; no dim
  while paused; `quickSelect(SETTINGS)` keeps the quick bar.
- `SettingsMachinePanelTest`: `present` publishes; SECTIONS at Root in PANEL requests no preview page; pushing
  `AddSource` / `PhoneSetup` / `ChannelEditor` / `RestorePreview` / `ImportProgress` dispatches `ExpandSettings` once;
  `present(FULL_SCREEN)` keeps path, cursor and overlays and reloads the page; `SettingsBackLadderTest` still green in
  both presentations.
- `SettingsPanelRulesTest`: `expands` for every route (the exhaustive `when` makes a new route a compile error);
  `RETUNE_ROWS` and `ARCHIVE_RETUNE_ROWS` are NEXT_TUNE in `SettingsTree`; `expandKey` cases.

`:ui` (Robolectric):
- `SettingsEffectsPanelApplyTest`: a REPREPARE intent in PANEL calls `applyFromPanel(false, …)` once after 400 ms; two
  toggles within 400 ms → once; a RETUNE row → `retune = true` with the route's playlist id; a full-screen REPREPARE
  intent → no immediate apply (today's path); `onClosed` after a panel apply does not apply again; `ReapplyOnExitTest`
  and `SettingsEffectsOrderTest` unchanged; result notices.
- `SettingsPanelBoundsTest` (1920 × 1080 design canvas): panel at (1376, 48, 496, 880); header, hairline, rows and band
  inside it; no row wider than 464; the band at its height; an open option list and the context menu inside the panel;
  at Default and with `UiTextScale.running = LARGEST` (no text clipped by the band, rows grow).
- `SettingsCompactRowTest`: value under the label, toggle without a second line, masked text as dots, badges on the
  value line, progress track width = row width, About header without the wordmark.
- `QuickBarPanelStyleTest`: the active pill's fill/border differ from the focused pill's.
- `RootTreeRuleTest`: a FULL + `settingsPanel` case (no layer/clip on `VideoLayer`'s ancestors; the panel is in the key
  sink).
- Score bug: `scorePlacementInput` with `settingsPanel` sets `picker`; an existing placement test with the picker box
  covers the rest.
- `AppRoot` reminder offset with the panel open.

`:app`:
- `LiveSettingsApplierImplTest`: `applyFromPanel` — idle → NOTHING_PLAYING; other playlist → OTHER_PLAYLIST; archive
  playing → `archive.reapply`; archive paused → WAITS_FOR_RESUME; error card → `retry`; retune → `retry`; plain →
  `reapplySettings`.

Gate: `./gradlew :core:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug lintAll` (ask before the full
run, per the owner's preference; targeted tests per WP first).

---

## 11. Work packages (disjoint files)

**Commit 0 (lead, Opus).**
- `SettingsMachine.kt`: `SettingsPresentation`, `SettingsUiState.presentation`, `present()`.
- `SettingsPages.kt`: `PageContext.panel`.
- `GuideStateContracts.kt`: `GuideUiState.settingsPanel`, `GuideEvent.ExpandSettings`.
- `PlayerContracts.kt`: `PlayerLayer.SETTINGS_PANEL` (append), `QuickBarState.panelOpen`,
  `PlayerStateMachine.settingsPanel(open)`.
- `ArchiveContracts.kt`: `PanelApplyResult`, `LiveSettingsApplier.applyFromPanel` (default).
- `SettingsPanelRules.kt` with signatures and the two row sets; `SettingsText` / `SetupText` keys
  (`EFFECT_REPREPARE_PANEL`, `EFFECT_RETUNE_PANEL`, `EFFECT_ARCHIVE_RETUNE_PANEL`, `PANEL_APPLIED`, `PANEL_RESTARTING`,
  `PANEL_WAITS`, `PANEL_OTHER_PLAYLIST`).
- Fakes: `FakeSettings` (testFixtures) and the player/guide fakes compile.

**WP-1, guide machine (Opus).** `GuideStateMachineImpl.kt`; `GuideSettingsPanelTest`.

**WP-2, player machine (Opus).** `PlayerStateMachineImpl.kt`; `PlayerSettingsPanelLayerTest`.

**WP-3, settings machine, effects, applier (Opus).** `SettingsMachineImpl.kt`, `SettingsPanelRules.kt` (bodies),
`SettingsEffectsImpl.kt`, `app/.../LiveSettingsApplierImpl.kt`; `SettingsMachinePanelTest`, `SettingsPanelRulesTest`,
`SettingsEffectsPanelApplyTest`, `LiveSettingsApplierImplTest`.

**WP-4, texts (Sonnet).** `WpaPages.kt` (`effectText` reads `ctx.panel`), `core/.../setup/pages/PlaylistPages.kt`,
`SettingsTexts.kt`, `SetupText.kt`, `ResourceSettingsTexts.kt` (and the setup texts' resource mapper),
`values/strings_settings.xml`, `values/strings_setup.xml`; builder tests for both presentations.

**WP-5, UI (Opus for layout, or Sonnet under this spec).** New `ui/.../settings/SettingsPanel.kt`;
`SettingsRowViews.kt` (`compact` parameter); `SettingsUi.kt` (`ProgressTrack` width parameter); `QuickBar.kt`;
`AppRoot.kt` (draw the panel, reminder offset); `FullScreenHost.kt` (the one `picker` line, **after** the score-sheet
work merges). Tests: `SettingsPanelBoundsTest`, `SettingsCompactRowTest`, `QuickBarPanelStyleTest`, the
`RootTreeRuleTest` case, the placement case. No edit to `PickerSheet.kt` or `ScoreSheet.kt`.

**WP-6, optional (Opus, playback; own reviewer).** Spike S1 (AudioTrack re-creation on re-prepare), then
`BoxRenderersFactory.kt`, `PlayerFactory.kt`, `PlaybackControllerImpl.kt` (`freshDecoders` on a queueing change),
`PlaybackRows.kt` (two effects → REPREPARE). Not needed for the panel to ship.

**Integration (lead).** Merge order: commit 0 → WP-1…WP-4 in parallel → WP-5 (after the score-sheet branch) → emulator
checklist → code review pass → record the reconciled decision in `docs/00-master-plan.md` (or
`docs/decisions/2026-11-26-player-settings-panel.md`).

---

## 12. Emulator checklist (compact states)

Fake panel `python3 tools/fakepanel/fakepanel.py --port 8080`, one Xtream playlist, one live channel playing.

1. FULL, Stream info on, quick bar → Settings: panel at Root (header "Settings", "☰ Full screen"), Settings pill active,
   stats at left, score bug (if shown top-right) moved; screenshot bounds against §5.1.
2. Playback → Video decoder → Software: within ~1 s the picture rebuffers once, notice "Applied to this channel", the
   stats' decoder line changes; log shows one `reapply_settings`.
3. Playlists → the playlist → Stream format → the other format: "Restarting the channel to apply…", a new cycle in the
   log, all through the broker (one connection at a time).
4. BACK ×3 from that page: playlist page → section → root → closed; quick bar focused on Settings, hides after 8 s.
5. "Lock settings" on: Settings chip → PIN pad over the video → panel.
6. Playlist page → User agent: the editor card centred with the IME; Back cancels; the panel keeps the cursor.
7. Playlists → Add playlist: expands to full-screen Settings at AddSource; BACK twice → FULL.
8. Text size Largest (restart), fake panel fault on the stream: the error card centre-left of the panel, unfocused;
   change Buffer → `retry`; BACK closes the panel and focuses the card; band text not clipped.

---

## 13. Not established from the code

- Whether `retry()` (`startCycle(c.specForRestart())`) always re-resolves the URL with the new playlist settings (user
  agent, stream format) or can reuse a cached resolution: WP-3 verifies in `PlaybackControllerImpl` and adds a test.
- Whether audio language and subtitle preferences are applied by a re-prepare (they would then be REPREPARE-able
  without a retune). The design retunes them, which is certain to apply.
- Whether a re-prepare re-creates the `AudioTrack` on every device (WP-6 spike S1).
- How the concurrent score-sheet work changes `FullScreenHost.kt` and whether it adds an overlay box the panel must also
  avoid (R7).
- The section-level one-line summaries for the band at Root do not exist; the band is empty there.
