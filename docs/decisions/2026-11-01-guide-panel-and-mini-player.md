# Guide: a video-only mini player and a focused panel without buttons

Date: 2026-11-01.
- Owner ruling, implemented as the lead read it. No proposer/reviewer round: the change removes UI; it adds no design.
- Design amended: `docs/design/m3-guide.md` §3.4.1, §3.4.3, §3.4.8, §3.9.1, §3.9.2, §3.13.4 (each note is dated "owner ruling 2026-11-01").

## Owner request (verbatim)

"in the guide can we remove all text in the mini player, I want just the video, also can we rmove thewtching playfrom start and more buttons from the highlight text beside th mini player , lets just have the name discription and art woork"

## Decision

### 1. Mini player: video only

Removed from the mini player (`ui/.../guide/screen/MiniPlayerOverlay.kt`):
- the state pill: LIVE (and its pulsing dot), CATCH-UP, Connecting…, Reconnecting n/m;
- the `CH {number}` pill;
- the M4 archive pill (PAUSED, −MM:SS);
- the bottom channel-name panel;
- in the still (nothing tuned, the Still setting, low memory, the HDR poster): the programme title and the prompt lines ("Press OK to watch", "HDR · Press OK for full screen", "Nothing playing. Press OK on a channel to watch."). The still now shows the channel logo alone. It shows nothing over the ambient hue when there is no logo: it does not fall back to the channel name.

What stays:
- **The compact error card.** A playback error still shows its reason and its next step, which is an engineering rule (CLAUDE.md). It is the only text the mini player can show.
- The overlay's test tags (`mini_overlay`, `mini_still`, `error_card`) and its debug composition counter.

The full-screen player is unchanged. It still draws its pills from `PillRules`. `PillRules`, `PlayerPill` and the stepped LIVE dot are shared code and stay. The LIVE dot moved to `ui/.../full/PlayerParts.kt`, its only reader now. The mini player's continuously pulsing version had no other reader and is deleted.

### 2. Focused-programme panel: no buttons

The panel (`ui/.../guide/screen/FocusedPanel.kt`) no longer draws the button row (Watching, Watch now, Watch, From start, Remind me, Reminder set, Watch channel now, More…). It still shows:
- the meta row: channel number and name, genre, ON NOW, catch-up days;
- the title, the time line, the description and the artwork.

**Focus can never land on an invisible button.** The state machine never enters the PANEL zone. This was the preferred option and needed no redesign, because PANEL was only ever a row of buttons.
- `GuideStateMachineImpl` no longer builds buttons. `panelKey`, `panelButton` and `buttonsFor` are deleted.
- Down from the tabs goes to the header chips (Group chip).
- Up from the header goes to the tabs (the Guide tab).
- A stale `Zone.PANEL` cannot occur. If it did, any key would return to the grid.

### How the removed buttons' actions stay reachable

| Removed button | Where its action is now |
|---|---|
| WATCHING / WATCH NOW | OK on the cell (§3.9.2 "OK in GRID": full screen, `tune`, or `retry()` on an error) |
| WATCH (past, in archive) | OK on the cell (`playArchive`); context menu → Watch |
| FROM START (on now, covered) | context menu → Watch from start; Red (START_OVER) |
| REMIND / REMINDER SET | OK on a future cell → programme sheet (Remind me / Reminder set); context menu → Remind / Cancel reminder; Red, REMIND, MEDIA_RECORD |
| WATCH CHANNEL NOW | OK on a future cell → sheet → Watch channel now; OK on a future gap (`tune`) |
| MORE… | the context menu itself: long OK or the Menu key (§3.9.3) |

The context menu still offers, in order and only where they apply: Watch, Watch from start, Remind or Cancel reminder, Add or Remove favourite, Hide channel, and the M5 lock items.

### Key-table changes (§3.4.3, `core/src/test/resources/guide/keys.csv`)

- TABS + DOWN → HEADER, Group chip. It used to go to PANEL when the panel had buttons, which was nearly always.
- HEADER + UP → TABS, Guide tab. It used to go to PANEL.
- Exit hint + DOWN dismisses the hint and acts as TABS + DOWN, so it now lands on HEADER.
- The PANEL rows are deleted, and the `panel:N` check is retired. The "stale tab" row (review S6) moved to the header: Up still lands on Guide after a visit to Search.
- Decision 2026-10-19 ruling 6 ("Down from the panel buttons lands on the Day chip") has nothing to act on any more. Days from home is now Back, Back, Down, Right, OK, which is still 5 keys. From row 0 it is still Up, Right, OK.

### Five-key rule (§3.4.8): an accepted regression

S11 (2026-10-14) added **More…** so that remotes without long press or a Menu key could reach the channel menu. Many HDMI-CEC remotes are like that. The owner removed More…, so with UP/DOWN/LEFT/RIGHT/SELECT/BACK alone a user can no longer reach these functions **from the guide**:
- add or remove a favourite (the Favourites filter in Groups still works);
- hide a channel;
- lock or unlock a channel.

A five-key route outside the guide still exists: Settings → Channels → the channel editor, where Right on a channel opens its menu (favourite, hide, lock; `ChannelEditorMachineImpl`). The editor is a separate surface, and this change does not check that route.

Everything else stays five-key reachable:
- tune and full screen (OK);
- catch-up (OK on an archive cell);
- reminders (OK on a future cell, then the sheet);
- groups, days, now, search, settings and exit.

From start on the programme on now needs long OK, Menu or Red.

`FiveKeyReachabilityTest` now runs two searches:
1. The six-key search asserts every header chip, overlay (Groups, Days, Sheet, Exit) and screen mode, as before. It no longer asserts the Menu overlay or the menu items. It also asserts that no panel button is ever offered and that the PANEL zone is never entered.
2. A new search adds CONTEXT_MENU and asserts every `MenuItem`, with what SELECT on each one does.

**Open for the lead:** whether five-key remotes need another way to the channel menu that draws no button over the panel. Two candidates:
- a "Channel options" row at the end of the programme sheet;
- the M6 channel editor reached from Settings, which already offers favourites, hide and lock per channel.

## Contract notes (append-only `GuideStateContracts.kt`)

- `PanelState.buttons` stays and is always `emptyList()`. Removing it would not be additive: it is a positional constructor argument (tests, `ShellRedrawTest`).
- `Zone.PANEL` stays and is never entered.
- `GuideUiState.panelFocused` stays and is always 0.
- `enum PanelButton` stays, unused by production code; its KDoc says it is retired.
- `GuideUiState.archive` and `tunedNow` stay populated. FULL and the `:app` instrumented tests read them; the mini player no longer does.

## Deleted with their last reader

- `PanelButtonView`, `PlayTriangle`, `panelButtonLabel` (ui).
- `GuideTexts.button` (core).
- `Pills`, `archivePill`, `MiniArchivePill` with `MINI_ARCHIVE_PILL_TAG`, `BottomPanel` with its gradient, the pulsing `LiveDot` variant, and the unused `hm()` (mini player).
- `BehindLabel` (`PlayerParts.kt`; its only reader was the mini player's archive pill).
- The unused `lowEnd` parameter of `GuideScreen`.
- Strings: `guide_btn_watching`, `guide_btn_watch_now`, `guide_btn_watch`, `guide_btn_from_start`, `guide_btn_more`, and all eight `guide_mini_*`. `guide_btn_remind`, `guide_btn_reminder_set` and `guide_btn_watch_channel_now` stay, because the programme sheet uses them.
