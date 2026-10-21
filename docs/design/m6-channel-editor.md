# M6 "Channel editor": design

Status: **implemented 2026-10-18/19**; reviews, revision log and the implementation record are at the end and in `docs/decisions/2026-10-18-channel-editor.md`. The owner's rulings
are recorded in §8. Owner request (verbatim):
"i also would like an easier or dedicated way to edit and hide groups and channels, keep the current method but also
create a better way to click multiple and hide and also be able to organize and display the channels like a full iptv
editor".

Read with: `docs/00-master-plan.md` (user data keyed by `(playlist_id, stable_key)`), `docs/01-design-reference.md`
(1 design px = width / 1920), `docs/design/m5-setup-settings.md` §3.1–§3.3, §3.7, §3.15 (settings kit, Channels and
groups, five-key rule), `docs/design/m3-guide.md` §3.8 (Groups panel). Facts were checked against `main` at `70da111`
plus the uncommitted working tree of 2026-10-18 (see §7.0: commit that first).

---

## 1. Goal and non-goals

**Goal.** One screen, driven by the D-pad alone (OK, Back, Menu, arrows), where a user who has just imported a
20,000-channel playlist can do what TiviMate's channel editor does. The user can:
- see every group and every channel, including hidden ones;
- tick many with OK and hide or show them in one go;
- reorder groups and channels;
- move channels to another group;
- rename a group or a channel;
- give a channel a number;
- add channels to Favorites;
- sort A–Z, by number, or back to the provider's order.

Every change is written to `library.db`, keyed by `(playlist_id, stable_key)` or `(playlist_id, kind, group_key)`. It
reaches the guide's Groups panel and lineups live, with no refresh.

Today's paths stay exactly as they are:
- the Groups panel's group menu (Hide / Lock / Move up / Move down);
- the guide's channel context menu;
- the Channel options page;
- Settings › Channels and groups › Groups / Hidden channels / Hide rules / Favorites lists.

**Non-goals.**
- No VOD or series editing: live and radio only (`channel.kind IN (0, 1)`), as the guide.
- No groups the user creates from nothing. Favorites lists already fill that role and show in the Groups panel.
- No choosing among several Favorites lists from the editor in v1: it adds to the default list (§3.3).
- No editing of stream URLs, logos, EPG ids or catch-up attributes. Fix guide data stays where it is.
- No touch or mouse.
- No playlist-wide undo history. Destructive one-shots ask first; everything else is reversible from the same screen.
- No changes to the hide-rule engine. Rule-hidden items are shown and explained, and undone at the rule.
- No renumbering by position. Numbers stay the provider's or the playlist numbering mode's; "Set number…" is how to
  change one (§3.7).

---

## 2. UX, screen by screen

### 2.0 Why a dedicated screen and not settings rows

The settings framework (`PageModel` + `SettingsRow` + `RowKind`, `core/.../settings/SettingsContracts.kt`) draws one
list per page in a 1340 px page pane, and `RowKind.Item` gives one menu per row. It cannot draw two linked lists side by
side. It has no tick state, no multi-row selection and no block move (`MoveState` moves one row).

So the editor is a **bespoke full-canvas page inside Settings**, built the way the M5 phone page is
(`SettingsRoute.PhoneSetup` drawn by a slot in `SettingsScreen`, keys through `SettingsShellLink.keyHandler`, wired in
`MainActivity.wireSettingsPages`). There is one difference from the phone page: the editor slot is drawn **inside**
`SettingsContent`, in this order:
1. the (private) `Header`;
2. the slot;
3. `ui.editor` / `ui.numberPad` on top.

The phone slot cannot be copied as it is, because it returns before `SettingsContent` and so before the overlays
(`SettingsScreen.kt:81-85`).

It reuses existing primitives only:
- `SettingsUi` tokens and geometry;
- the top-level `Chip`, `Line` and `KeyCap` in `ui/.../guide/screen/GuideUi.kt`, and `FooterHints`;
- `DesignTokens.Colors`;
- the settings `CheckBox` and `BadgeChip` (made `internal`);
- `MoveModeRow`'s lift look, without `Modifier.shadow`, which is a `graphicsLayer`;
- `ChannelLogo` (`ui/.../full/PlayerParts.kt`), which gains a `(url, name)` overload because today it takes a
  `GuideChannel`;
- the existing `TextEditorOverlay` and `NumberPad`, owned by the settings machine;
- `ConfirmCard`'s look.

No `Popup`, no `Dialog`, no tv-material focusables, no `graphicsLayer`, no blur (plan §3, M5 §3.1). Settings covers the
hole (`HoleMode.COVERED`) over an opaque canvas, so the editor is never an ancestor of the video surface.

### 2.1 Entry points

| From | How | Lands on |
|---|---|---|
| Settings › Channels and groups | new **first** row `channels.editor` "Channel editor" (Link), value "Hide, sort and move channels" | `ChannelEditor(pid)`, GROUPS pane, first group |
| Guide › Groups panel › group menu (long OK on a group) | new last item **"Edit channels…"** (`GroupMenuItem.EDIT`) | `ChannelEditor(pid, groupKey)`, CHANNELS pane, that group focused |
| Guide › channel context menu › Channel options | unchanged | `Channel(identity)` (the per-channel page stays) |

Both entries go through the guide's `openSettingsGated(route)`, so "Lock settings" asks for the PIN exactly as it does
for Channel options today.

The route's parents are `[Root, Section(CHANNELS)]`: `SettingsMachineImpl.parentOf` falls through to
`SettingsTree.sectionOf`, as for `Channel(identity)`. So **Back from the editor goes to Channels and groups, not back
to the guide, even when the editor was opened from the guide**. This matches Channel options today
(`GuideStateMachineImpl` `CHANNEL_OPTIONS`). The Guide key leaves to the guide from any depth (M5 N11), and the footer
names it.

With two or more playlists, the Channels section's `channels.playlist` chooser picks the playlist first. The guide entry
passes the group's own playlist.

### 2.2 Layout (1920 × 1080 design px)

The page takes the whole canvas below the settings header. There is no sections pane, as with `PAGE_SOLO_X` /
`PAGE_SOLO_W`.

| Element | Geometry | Style |
|---|---|---|
| Header | as Settings: "Settings" title (64, 48) `SettingsUi.Title`; breadcrumb (64, 104) `SettingsUi.Breadcrumb` "Settings › Channels and groups › Channel editor · {playlist}"; clock right 64 | unchanged kit |
| Groups pane | x 64, y 144, w 560, h 800 | glass (`GlassFill`, 1 px `GlassBorder`, no blur), radius 24, padding 16 |
| Channels pane | x 648, y 144, w 1208, h 800 | same |
| Pane title line | pane top + 16, h 40 | name 30/600 (`GuideUi.sans(30, SemiBold)`); counts mono 22 `.55` after it ("64 · 12 hidden · 2 selected") |
| Toolbar | pane top + 64, h 48 | `Chip`s, sans 22, padding 10 × 18, gap 12; focused chip = FG fill, dark text, 3 px accent ring |
| Rows | from pane top + 128; h 72 (`ITEM_ROW_H`), gap 8, radius 14, padding 0 × 20 | 8 rows visible: (800 − 128 − 16) / 80 = 8.2 |
| Tick box | x + 20, 24 × 24, radius 4 | settings `CheckBox`: accent fill + FG tick when on; 2 px `.45` ring when off |
| Group row | name 26/600 at x + 60; value mono 22 `.65` right-aligned ("120" or "120 · 8 hidden"); badges left of the value | badges: `BadgeChip` mono 18, radius 7, `.22` border |
| Fixed group-pane rows | "All channels" (row 0) and "Results for '…'" (row 1, when present): no tick box, name 26/600, count mono 22 | not tickable, not movable |
| Channel row | number mono 22 `.5` at x + 60 (w 80, right-aligned); logo slot 96 × 48 radius 8 at x + 152 (`ChannelLogo(url, name)`, off when "Channel logos in guide" is off); name 26/600 at x + 268; trailing detail sans 22 `.55` ("from Sports", "was ESPN HD"); badges at the right edge | |
| Locked-out note | channels list row 0 when some channels are left out by the PIN (§2.6) | `.45` text 22, lock glyph; focusable; OK asks the PIN |
| Hidden item | text at `.45`, `HIDDEN` badge; rule-hidden: `RULE` badge | still focusable and tickable |
| Focused row | FG fill `#EEF1F6`, dark text `#0A0D13`, 3 px accent ring `#5CA8F2` | as every settings row |
| Current group (focus in Channels) | `.08` fill + 4 × 32 accent bar at x + 8 | as the settings "current section" |
| Move mode | lifted rows: 1 px accent border, `.12` fill, `↕` glyph 22 in the tick slot | `MoveModeRow` look; the block stays contiguous |
| Action menu | 480 wide (wider than the settings `MENU_W` 420: the lines carry counts), anchored to the focused row's right edge inside its pane (the `menuTop` rule of `SettingsScreen`) | `SettingsUi.CardFill`, 1 px `.12` border, radius 24, padding 16; title 22/600 `.7`; items 60 high, label 26, count mono 22 `.55` right; disabled `.4` with the reason as a second line, 20 `.55`; a "…" title and no lines while the summary loads (§3.3) |
| Group picker | card 720 × ≤ 760, centered (x 600, y 160) | same fill; title 30/600 "Move 3 channels to"; paged rows 64 |
| Confirm card | 720 wide, centered | `ConfirmCard` look (title 36/600, body 26, focus on the safe button) |
| Notice line | x 64, **y 956**, h 40 (under the panes, above the footer; it never covers row 8) | `SettingsUi.Notice` 24 `.7`, fades after `NOTICE_MS` (3,000) |
| Footer hints | left 64, bottom 40 | `FooterHints` with `KeyCap`s, driven by the focused context (§2.4) |

Text sizes follow the owner's 10-foot ruling already in `SettingsUi` (row label 28, value 26). Rows use 26/600 because
two columns of content share a 72 px row (the same as the guide's 26/600 cell title).

### 2.3 Screens (ASCII, to scale in spirit, not in characters)

**E1: Groups pane focused** (entered from Settings, two groups ticked)

```
Settings                                                                                     21:04
Settings › Channels and groups › Channel editor · MyProvider
┌─ Groups  64 · 12 hidden · 2 selected ┐ ┌─ UK | Entertainment  120 channels · 8 hidden ─────────────┐
│ [Actions] [Hidden groups: Shown] [Sort…]│ │ [Actions] [Show: All ▾] [Sort…] [Find…]                    │
│   All channels               20,140  │ │ ☐ 101 [logo] BBC One HD                                      │
│█☐ UK | Entertainment       120 · 8  █│ │ ☐ 102 [logo] BBC Two HD                                      │
│ ☑ UK | Sport                    64   │ │ ☐ 103 [logo] ITV 1 HD                       was ITV HD UK    │
│ ☑ UK | Kids                     40   │ │ ☐ 104 [logo] Channel 4 HD                    HIDDEN          │
│ ☐ US | News        HIDDEN       52   │ │ ☐ 105 [logo] Sky Mix              ★                          │
│ ☐ Adult            🔒 18+       90   │ │ ☐ 106 [logo] Dave                            from UK | Sport │
│ ☐ XXX VOD     RULE HIDDEN      300   │ │ ☐ 107 [logo] E4                                              │
│ ☐ Ungrouped                     12   │ │ ☐ 108 [logo] More4                                           │
└──────────────────────────────────────┘ └──────────────────────────────────────────────────────────────┘
 3 channels hidden                                                    (notice line, y 956)
 ↑↓ Browse   OK Select   → Channels   ☰ Group actions   Back Done   Guide key: back to the guide
```

**E2: Channels pane focused, three channels ticked**

```
┌─ Groups ──────────────────────────┐ ┌─ UK | Entertainment  120 channels · 8 hidden · 3 selected ────┐
│ ...                               │ │ [Actions] [Show: All ▾] [Sort…] [Find…]                        │
│▌  UK | Entertainment     120 · 8  │ │ ☑ 101 [logo] BBC One HD                                         │
│   UK | Sport                  64  │ │ ☐ 102 [logo] BBC Two HD                                         │
│ ...                               │ │█☑ 103 [logo] ITV 1 HD                          was ITV HD UK   █│
│                                   │ │ ☐ 104 [logo] Channel 4 HD                       HIDDEN          │
│                                   │ │ ☑ 105 [logo] Sky Mix              ★                             │
└───────────────────────────────────┘ └─────────────────────────────────────────────────────────────────┘
 ↑↓ Browse   OK Select   ← Groups   → or ☰ Actions   Back Clear selection
```

**E3: Channel action menu** (→ or Menu on E2; it applies to the 3 ticked channels)

```
                                             ┌ 3 channels selected ───────────────┐
                                             │█ Hide                           3 █│
                                             │  Move to group…                    │
                                             │  Reorder                           │
                                             │  Add to Favorites                  │
                                             │  Lock                              │
                                             │  Select all in this list     120   │
                                             │  Find…                             │
                                             │  Show…                             │
                                             │  Sort…                             │
                                             │  Reset to provider settings…       │
                                             └────────────────────────────────────┘
```

The lines follow the order ruled in §2.4. With nothing ticked, the menu acts on the focused channel: its title is the
channel's name, and it adds **Rename…** and **Set number…** after Lock. It also adds **Use the provider's number** when
a custom number is set. "Hide" becomes "Show" when every target is hidden. With a mix, "Hide 2" stays line 1 and
"Show 1" goes after Reorder, so Move to group stays on line 2. The lines come from a summary computed on IO (§3.3), so
the counts are right even under Select all.

**E4: Move to group picker**

```
                 ┌ Move 3 channels to ───────────────────────────────────┐
                 │   UK | Entertainment               Already here (dim) │
                 │█  UK | Sport                                   64    █│
                 │   UK | Kids                                    40     │
                 │   US | News                       HIDDEN       52     │
                 │   Adult                           🔒 18+       90     │
                 │   ...                                (paged, CH± ±8)  │
                 └───────────────────────────────────────────────────────┘
```

- Picking a hidden target is allowed; its row says "Hidden: the channels won't show until you show the group".
- Picking a locked target is allowed, because it adds protection.
- Moving **out of** a locked group asks for the PIN when one is set (§2.6).
- The picker lists the same groups, in the same order, as the Groups pane, and shares its page cache (§3.3).

**E5: Reorder (move mode), 3 channels lifted**

```
┌─ UK | Entertainment  Moving 3 · position 14 of 120 ─────────────────────────────┐
│   112 [logo] Gold                                                                │
│ ↕ 101 [logo] BBC One HD                                         (lifted, accent) │
│ ↕ 103 [logo] ITV 1 HD                                                            │
│ ↕ 105 [logo] Sky Mix                                                             │
│   113 [logo] Alibi                                                               │
└──────────────────────────────────────────────────────────────────────────────────┘
 ↑↓ Move   CH± Move 8   OK Drop   Back Cancel
```

- Lifting gathers the explicitly ticked rows, in list order (the machine keeps each tick's list index, §3.3), into one
  block at the focused row's position.
- The block's rows are carried in the move state, so it draws at any position without its source pages loaded.
- Nothing is read or written while moving; OK writes once (§4).
- Reorder is not offered under Select all (§2.6).
- Group reorder works the same way in the Groups pane, over real groups only. The block is clamped below the fixed rows
  "All channels" and "Results".

**E6: Toolbar choice** (OK on `Show: All ▾`, or the menu line **Show…**). The settings inline choice list opens under
the chip (radio 18, option 26, detail 22 `.55`), drawn inside the pane over the rows. Options: **All** / **Shown only**
/ **Hidden only**.

**Sort…** (toolbar chip or menu line) is an **action**, not a state, because a sort is a one-shot write. It opens the
same choice list:
- channels: **Name A–Z** / **Name Z–A** / **Number** / **Provider order**;
- groups (from the Groups toolbar's `Sort…` or the group menu's **Sort groups…**): **Name A–Z** / **Provider order**.

It asks first when a custom order exists ("Sort UK | Entertainment by name? Your own order for this group is
replaced."; `EditorGroupRow.customOrder` says whether one exists).

**E7: Locked group** (a PIN is set and the session is locked). The channels pane shows only the placeholder "Adult is
locked. Press OK to enter your PIN." OK opens the guide's one PIN prompt.

**E8: Find** (toolbar `Find…`, or the menu line **Find…**). The settings text editor overlay opens ("Find channels",
`TextHint.NAME`). Done adds a fixed row **"Results for 'espn' · 14"** as row 1 of the Groups pane, focused, with its
channels on the right. Results cover every group, and match both provider and custom names. Back on that row drops it.

The Groups pane always starts with **All channels**: the playlist's whole live lineup, in the guide's All order, which
follows the user's order (§3.7).

All channels and Find results support tick, Hide/Show, Move to group, Favorites, Lock, Rename, Set number and Find.
- Reorder and Sort are disabled there, with a reason (§5.2).
- In the Groups pane, the menu on these two rows has only **Edit channels (→)**, **Find…** and, for Results,
  **Drop results**. Hide, Lock and Rename are left out there, not shown disabled.

### 2.4 Keys

Focus is virtual only, as in the guide and Settings: one key sink, and the editor machine maps `BoxAction`s to state.
`ChannelEditorMachine.act` returns `false` only for:
- BACK at the bottom of the ladder;
- OPEN_GUIDE;
- EXIT.

Every other key returns `true`, including unlisted ones. A `false` LEFT would reach the shell's `pageKey` and pop the
page (`SettingsMachineImpl.kt:363-365`); the key table tests this. Long Back is ignored, as in Settings.

GROUPS pane (no layer open):

| Key | Behaviour |
|---|---|
| ↑ / ↓ | Previous/next row (hold: settings repeat rate, accelerating to 3 rows after `HOLD_ACCEL_MS` 600). ↑ on row 0 → toolbar. The channels pane follows the focused row after `FOLLOW_DEBOUNCE_MS` (150, a machine deadline, §3.3) |
| OK | On a group: tick / untick. On All channels or Results: as → |
| → | Focus the channels pane (cursor memory per list, else row 0) |
| ← | Nothing (left edge; consumed) |
| Menu / long OK | Group action menu (the ticked groups, else the focused one; All/Results: their short menu, §2.3) |
| CH± / PAGE± | Page ±8 rows |
| Digits | Ignored |
| FAVOURITES (green) | Ignored |
| BACK | Ladder (§2.5) |

CHANNELS pane (no layer open):

| Key | Behaviour |
|---|---|
| ↑ / ↓ | Previous/next channel (hold accelerates as above); ↑ on row 0 → toolbar |
| OK | Tick / untick (on E7 or the locked-out note: ask for the PIN) |
| ← | Focus the Groups pane (ticks kept, because the list does not change) |
| → | Channel action menu (the path without a Menu key) |
| Menu / long OK | Channel action menu |
| CH± / PAGE± | Page ±8 rows |
| Digits | Jump to the channel with that number in the current list (the number-entry look; 1.5 s after the last digit; `Located` event, §3.3); "No channel 501 in this list" when there is none |
| FAVOURITES (green) | Toggle Favorites on the selection (or the focused channel): one press. An accelerator, never the only path |
| BACK | Ladder (§2.5) |

Layers:

| Layer | ↑ / ↓ | ← | → | OK | CH± / PAGE± | Menu / long OK, digits | BACK |
|---|---|---|---|---|---|---|---|
| Toolbar | ↓ back to row 0; ↑ nothing | previous chip | next chip | the chip: Actions → menu, Show → choice list, Sort… → sort list, Find… → editor overlay, Hidden groups → toggle | nothing | ignored | back to row 0 |
| Action menu | lines, **clamped (no wrap)**, disabled lines focusable and showing their reason | close | nothing | run the line (confirm / PIN / picker / overlay first when it needs one) | CH+ / PAGE_UP → first line, CH− / PAGE_DOWN → last line | ignored | close |
| Choice list (E6) | options | close unchanged | nothing | choose, close | nothing | ignored | close unchanged |
| Group picker (E4) | rows | close | nothing | move there, close | ±8 rows | ignored | close |
| Move mode (E5) | move the block one place (hold repeats) | nothing | nothing | drop: one write | move the block ±8 | ignored | cancel; the block returns |
| Confirm card | nothing | previous button | next button | the focused button (focus starts on the safe one) | nothing | ignored | cancel |
| Text editor / number pad | owned by the settings machine, as today (`EditorState`, `NumberPadState`) | | | | | | |

**Menus (ruled order, no wrap).**
- **Channel menu:** Hide or Show · Move to group… · Reorder · Add to (Remove from) Favorites · Lock or Unlock ·
  Rename… · Set number… · Use the provider's number (only when a custom number is set) · Select all in this list ·
  Find… · Show… · Sort… · Reset to provider settings….
  - With a mixed selection, the second of Hide/Show goes after Reorder.
  - Rename…, Set number… and Use the provider's number appear only with exactly one target.
  - There is **no "Select none"**: Back clears the selection in one press (§2.5), and the footer says so.
- **Group menu:** Hide or Show · Reorder · Lock or Unlock · Rename… · Edit channels (→) · Sort groups… · Select all ·
  Reset….
- **Groups toolbar "Actions" chip:** opens the group menu for the **ticked** groups. With no group ticked it is
  disabled, with "Tick groups with OK first." (the focused group is reached with Menu / long OK on its row).

**Press budget from a focused row** (Menu, long OK and → count as one press each):

| Action | Presses |
|---|---|
| Tick / untick | 1 (OK) |
| Hide or Show the selection | 2 (→ or Menu, OK) |
| Favorites (accelerator) | 1 (green key) |
| Move to group… (then the picker) | 3 (Menu, ↓, OK) |
| Group: Reorder | 3 (Menu, ↓, OK) |
| Channel: Reorder | 4 (Menu, ↓↓, OK) |
| Reset… (last line) | 3 (Menu, CH−, OK), then the confirm card |
| Favorites from the menu | 5 |
| Lock / Unlock | 6 |
| Rename… / Set number… | 7 / 8 (single channel) |
| Select all, Find…, Show…, Sort… | from the end: Menu, CH−, ↑ × k, OK = 4–7; or ↑ to the toolbar from row 0 |

So "≤ 3 presses" holds for what the owner asked for (tick several and hide, and move) and for Reset. The rest are
reachable from any row through the menu, with no Menu key needed, because → opens the channel menu. CH± in the menu is
the only accelerator besides the green key, and neither is ever the only path.

Key-map conflicts: none with `DefaultKeyMap`.
- CH± (ZAP_UP/ZAP_DOWN) and PAGE± already mean "page" in Settings.
- Menu and long OK are CONTEXT_MENU.
- FAVOURITES is the green key (`DefaultKeyMap.kt:49`).

### 2.5 Back ladder (leave a mode before leaving the page)

Each Back takes the first step below that applies:
1. Close the open layer: picker, choice, confirm or menu. The text editor and number pad are closed by the settings
   machine.
2. Cancel move mode.
3. Leave the toolbar, back to the rows.
4. In the channels pane: clear a non-empty selection, with the notice "3 channels unselected".
5. Leave the channels pane for the Groups pane.
6. In the Groups pane: clear the group ticks.
7. With Find results focused: drop them.
8. Leave the editor (the shell pops to Channels and groups).

From the deepest state (a picker over a selection in Find results) that is at most 7 Backs, inside M5's 12-Back limit
(§3.15 c). Nothing is ever lost on Back: every write already happened when its action ran (queued writes still run,
§3.3), and Back only drops ticks and modes.

### 2.6 Rules that the screen enforces

- **Selection scope.** Ticks belong to one `(list, filter)`. Changing the list (another group, All, Results) or the
  filter clears them, with the notice "3 channels unselected". To act across groups, tick in All channels or in Find
  results.
- **Select all** is symbolic ("all of this list and filter, except these"), never a list of keys (§4).
- **Reorder** needs a real group, the filter **All**, and explicit ticks (or none). It is disabled otherwise, with a
  reason (§5.2):
  - Select all in a big list is not a hand move ("use Sort, or tick fewer channels");
  - a filtered or mixed list has no single order to drop into.
- **Sort** needs a real group and works whatever the filter is: it orders the whole group.
- **Rule-hidden** items are tickable, but Show skips them. The menu says "2 stay hidden by a rule", and the row's
  detail names the rule. They are shown again at the rule (Hide rules, advanced), as M5 §3.7.2 says.
- **Parental** (M5 §3.9.1, C-B2: lowering protection asks for the PIN). The session is "locked" when a PIN is set and
  not entered (LOCKED_AUTO / LOCKED_MANUAL_ONLY). While it is locked:
  - (a) A locked group's channels are not listed until the PIN is entered (E7).
  - (b) Every channel list (a group, All channels, Find results) uses the guide's access predicate. Locked channels are
    left out of the list, and a note row heads it: "Locked channels are not shown. Press OK to enter your PIN." Select
    all and every `ChannelTarget.AllOf` write re-run with the same access, so they never act on channels the user cannot
    see.
  - (c) **Unlock**, **Reset** (it clears locks) and **Move to group out of a locked group** ask for the PIN
    (`PinPurpose.SettingsAction` through `SettingsShellLink.requestPin`). **Lock** never asks.
  - (d) The data layer refuses the same three under `ParentalSessionImpl.refusesLowering()` (defence in depth), and
    the machine shows the action's PIN text.
- With no PIN (FILTERED_NO_PIN), locked groups list their channels with the amber `18+` chip, as the Groups panel does
  (U-S11).
- Unlocking a channel inside a locked group is disabled, with "Locked by its group, {group}. Unlock the group to unlock
  this channel."
- Group and channel locks go through `ParentalControls`, the one parental write path (bulk forms, §3.5).
- **Badges and counts.**
  - Group value: "120" or "120 · 8 hidden" (hidden by you or by a rule).
  - Badges: `HIDDEN`, `RULE`, `LOCKED` (with the lock glyph), `18+`, and `★` (in the default Favorites list).
  - Detail text: "from {group}" for a moved channel, "was {name}" for a renamed one.
  - Pane title counts: "64 · 12 hidden · 2 selected" and "120 channels · 8 hidden · 3 selected".

---

## 3. Contracts

### 3.1 Route, rows and the simple-row budget (rulings Q1–Q4 applied)

- **Route.** `SettingsContracts.kt` gains `data class ChannelEditor(val playlistId: Long, val groupKey: String? = null)
  : SettingsRoute` (append-only).
  - `SettingsTree.sectionOf` (`SettingsTree.kt:64-76`, exhaustive) maps it to `SectionId.CHANNELS`.
  - `SettingsMachineImpl.parentOf` needs no new branch.
  - `ReviewFixRowsTest.sectionOfMatchesParentChain` gains the route.
  - `DiagPages.kt` ends in `else` and is not touched.
- **Rows.** `ChannelsRows.kt` gains `val EDITOR = RowId("channels.editor")` and
  `RowSpec(EDITOR, CHANNELS, SIMPLE, LIVE, "D")`. `ChannelsPages.section` emits it first, as
  `RowKind.Link(SettingsRoute.ChannelEditor(pid))`.
- **Budget (owner ruling Q3; §3.3.13 stays at 35).** `channels.hidden` moves from SIMPLE to **ADVANCED**. Its page
  still exists; in the simple view, the editor's "Show: Hidden only" does the same job, with bulk Show.
  - The Channels and groups simple rows stay at 4: editor, groups, favourites, open guide on.
  - `SimpleRowCountTest` (`core/src/test/.../settings/state/`) keeps asserting 35, with the new row set.
  - The lead updates `m5-setup-settings.md` §3.3.5 and §3.3.13 at commit 0.
- **The editor page has no settings rows** (its own screen draws it). Its `PageModel` is
  `PageModel(route, title = "Channel editor", rows = emptyList(), version)`, from `ChannelEditorPageSource`. That keeps
  the header, the breadcrumb, cursor memory and the open trace working.
- **Guide.** `GroupMenuItem.EDIT` is appended (`GuideStateContracts.kt`), and `GroupMenuItem.toAction()`
  (`GuideStateMachineImpl.kt` ~2332, exhaustive) gains `EDIT -> null` at commit 0.
  - `GuideStateMachineImpl.groupMenu` appends EDIT.
  - SELECT on it runs `openSettingsGated(SettingsRoute.ChannelEditor(src.playlistId, src.groupKey))`.
  - The M3 five-key test already excludes the group menu (long OK, m3 §3.15). The editor is reachable on four keys
    through Settings.

### 3.2 Settings shell seams (additive; `core/.../settings/state/SettingsPages.kt`, `SettingsMachineImpl.kt`)

```kotlin
interface SettingsShellLink : SettingsPageNavigation {
    // existing members unchanged …

    /**
     * Opens the settings text editor overlay for a page drawn by another screen. [validate] null = the hint's rule
     * (HintValidator); the channel editor's Rename passes `{ null }`, so an empty name ("use the provider's name") commits.
     * [onClosed] is called exactly once: the committed text (even when equal to [initial]), or null on cancel, on
     * EditorBack-to-cancel and when closeOverlays() runs (OPEN_GUIDE, EXIT, push, replace, pop, onLeave).
     */
    fun editText(
        label: String, initial: String, hint: TextHint, chips: List<String> = emptyList(),
        validate: ((String) -> String?)? = null, onClosed: (String?) -> Unit,
    ) { onClosed(null) }

    /** Opens the settings number pad; [onClosed] is called exactly once: the number, or null (cancel, closeOverlays). */
    fun editNumber(label: String, value: Int?, min: Int, max: Int, onClosed: (Int?) -> Unit) { onClosed(null) }

    /**
     * Asks the guide's one PIN prompt (PinPurpose.SettingsAction(token)). [onResult] is called exactly once: true when
     * granted or when no PIN is set (PinGate.request returned false), false when refused or when closeOverlays() runs first.
     */
    fun requestPin(onResult: (Boolean) -> Unit) { onResult(false) }
}
```

The defaults answer the callback, so test doubles never hang.

`SettingsMachineImpl` changes:
- It stores `extTextCallback` with the editor (`EditorState.row = RowId("ext.edit")`) and calls it in `onEditorClosed`:
  - on cancel, with null;
  - after the caller's validator passes, with the text, **including unchanged text**. Today the unchanged case emits
    nothing (`:623`).
- It stores `extNumberCallback` with the pad (`NumberPadState.row = RowId("ext.number")`) and calls it from the pad's
  OK (`commitPad`, after the range check) or with null on close. A pad cannot commit "empty" (`commitPad` rejects empty
  digits), so the editor clears a number through its own menu line **Use the provider's number**, a
  `SetNumber(…, null)` write.
- It stores `extPinCallback` keyed by `pinToken`. `onPinResult` calls it with `e.granted` on **both** outcomes; today
  only the granted step runs (`:441-447`). A token that has gone stale gets `false`.
- `closeOverlays()` (`:918-923`) calls any pending `ext*` callback with null / false before it drops `editor`,
  `numberPad` or the pending PIN.
- **Key order fix:** `act` consults `keyHandler` only when `numberPad`, `confirm`, `menu`, `move` and `choice` are all
  null. Today it runs before them (`:169`), while the interface doc says "after overlays" (`SettingsPages.kt:121-123`).
  The phone page never opens those overlays, so its behaviour is unchanged. The `editor != null` branch already runs
  first.

`MainActivity.wireSettingsPages` chains the handler: `PhoneSetup` is handled as today; `ChannelEditor` goes to
`channelEditor.act(act)`.

### 3.3 Core: the editor machine (`core/src/main/kotlin/app/thebox/core/editor/`)

The machine is toolkit-agnostic, main-thread, pure state, like `SettingsMachineImpl` and `GuideStateMachineImpl`.
Every key table is a JVM test.

```kotlin
package app.thebox.core.editor

enum class EditorPane { GROUPS, CHANNELS }
enum class EditorFilter { ALL, SHOWN, HIDDEN }
enum class ChannelSort { NAME_AZ, NAME_ZA, NUMBER, PROVIDER }
enum class GroupSort { NAME_AZ, PROVIDER }

/** What the channels pane lists, and the fixed rows at the top of the Groups pane. */
sealed interface EditorList {
    data object All : EditorList
    data class Find(val query: String) : EditorList
    data class Group(val groupKey: String) : EditorList
}

/** Ticks: transient UI state, scoped to the pane's current (list, filter); changing either clears them. */
sealed interface Selection {
    data object None : Selection
    /** Explicit ticks: stable key (channels) or group key (groups) → its list index when ticked (block order, §2.3 E5). */
    data class Keys(val keys: Map<String, Int>) : Selection
    /** "Select all in this list" minus [except]. Not liftable (Reorder disabled, §2.6). */
    data class AllExcept(val except: Set<String>) : Selection
}

data class EditorGroupRow(
    val groupKey: String, val name: String, val providerName: String,
    val channels: Int, val hiddenChannels: Int,
    val hidden: Boolean, val ruleHidden: Boolean, val locked: Boolean, val adult: Boolean, val notProtected: Boolean,
    val renamed: Boolean, val customOrder: Boolean,
)

data class EditorChannelRow(
    val stableKey: String, val name: String, val providerName: String,
    /** The number the guide shows (custom, else the playlist's numbering mode); [customNumber] non-null when set here. */
    val number: Int?, val customNumber: Int?, val logoUrl: String?,
    val hidden: Boolean, val ruleHidden: Boolean, val rulePattern: String?,
    val locked: Boolean, val lockedByGroup: String?,
    /** In the default Favorites list (FavouritesRepository.defaultList()). */
    val favorite: Boolean, val movedFrom: String?, val renamed: Boolean,
)

/** A paged window over an ordered list (§4): [count] rows, pages of PAGE (32) loaded around the cursor. */
data class EditorWindow<T>(val version: Long, val count: Int, val hidden: Int, val pages: Map<Int, List<T>>, val cursor: Int, val top: Int)

/**
 * The Groups pane: [head] fixed rows (All, then Find when present) followed by the paged real groups.
 * Pane index i < head.size is head[i]; otherwise group index i − head.size in [groups]. Cursor, ticks and move-mode
 * clamps use the pane index; only group rows are tickable and movable.
 */
data class GroupsPane(val head: List<EditorList>, val groups: EditorWindow<EditorGroupRow>?, val cursor: Int, val top: Int)

enum class EditorAction {
    HIDE, SHOW, MOVE_TO_GROUP, REORDER, ADD_FAVORITE, REMOVE_FAVORITE, LOCK, UNLOCK, RENAME, SET_NUMBER, CLEAR_NUMBER,
    SELECT_ALL, FIND, FILTER, SORT, RESET, EDIT_CHANNELS, DROP_RESULTS,
}
data class MenuLine(val action: EditorAction, val count: Int?, val enabled: Boolean, val reason: EditorText?)

/** [lines] empty while [loading] (the summary is on its way); the card shows a "…" title meanwhile. */
data class EditorMenu(val pane: EditorPane, val title: String, val lines: List<MenuLine>, val focused: Int, val loading: Boolean)
/** Shares the Groups pane's group window (same version, same cache); only its own cursor. */
data class GroupPicker(val cursor: Int, val top: Int, val moving: Int, val fromGroupKey: String?)
sealed interface Lifted {
    data class Channels(val rows: List<EditorChannelRow>) : Lifted
    data class Groups(val rows: List<EditorGroupRow>) : Lifted
}
data class EditorMove(val pane: EditorPane, val lifted: Lifted, val fromIndex: Int, val index: Int)
data class EditorConfirm(val kind: ConfirmKind, val args: List<String>, val pending: EditorWrite, val focused: Int = 1)
enum class ConfirmKind { SORT_REPLACES_ORDER, RESET_CHANNELS, RESET_GROUPS, PROVIDER_ORDER }
data class EditorChoice(val kind: ChoiceKind, val focused: Int)
enum class ChoiceKind { FILTER, CHANNEL_SORT, GROUP_SORT }

/** The action menu's facts over a target, counted on IO with the write's own predicate. */
data class SelectionSummary(val total: Int, val hidden: Int, val ruleHidden: Int, val locked: Int, val lockedByGroup: Int, val favorite: Int, val fromLockedGroup: Int)

data class ChannelEditorUiState(
    val playlistId: Long, val playlistName: String,
    val pane: EditorPane = EditorPane.GROUPS,
    val toolbar: Int? = null,                       // focused chip index; null = rows
    val groups: GroupsPane,
    val showHiddenGroups: Boolean = true,           // the Groups toolbar's "Hidden groups: Shown / Not shown" chip
    val groupSelection: Selection = Selection.None,
    val list: EditorList, val filter: EditorFilter = EditorFilter.ALL,
    val channels: EditorWindow<EditorChannelRow>?,  // null = loading
    val channelSelection: Selection = Selection.None,
    val lockedList: Boolean = false,                // E7
    val lockedOut: Int = 0,                         // > 0: the "Locked channels are not shown" note row heads the list
    val menu: EditorMenu? = null, val picker: GroupPicker? = null, val move: EditorMove? = null,
    val confirm: EditorConfirm? = null, val choice: EditorChoice? = null,
    val digits: String? = null,                     // number jump in progress
    val pendingWrites: Int = 0,                     // FIFO length (0 = idle); the title shows "Saving…" while > 0
    val notice: String? = null,
    val empty: EditorText? = null,                  // the pane's empty or error text (§5.2)
    val gone: Boolean = false,                      // E_PLAYLIST_GONE: only BACK works
    val footer: List<app.thebox.core.guide.FooterHint>,
)

sealed interface ChannelEditorEvent {
    data class Act(val action: app.thebox.core.guide.GuideEvent.Act) : ChannelEditorEvent
    data class Groups(val version: Long, val count: Int, val hidden: Int, val cursorIndex: Int?) : ChannelEditorEvent
    /** Rows of the group window (the pane and the picker share it). */
    data class GroupRows(val version: Long, val from: Int, val rows: List<EditorGroupRow>) : ChannelEditorEvent
    data class Channels(
        val list: EditorList, val filter: EditorFilter, val version: Long,
        val count: Int, val hidden: Int, val lockedOut: Int, val listLocked: Boolean,
        /** The index of the key the machine asked to keep (after a sort, move or reload), found on IO; null = keep the index. */
        val cursorIndex: Int?,
    ) : ChannelEditorEvent
    data class ChannelRows(val version: Long, val from: Int, val rows: List<EditorChannelRow?>) : ChannelEditorEvent
    data class Summary(val token: Long, val summary: SelectionSummary) : ChannelEditorEvent
    data class Located(val number: Int, val index: Int?) : ChannelEditorEvent
    data class Written(val write: EditorWrite, val result: WriteResult) : ChannelEditorEvent
    data class LoadFailed(val what: EditorPane) : ChannelEditorEvent
    data object PlaylistGone : ChannelEditorEvent
    data class Access(val pinSet: Boolean, val unlocked: Boolean) : ChannelEditorEvent
    data class PinResult(val granted: Boolean) : ChannelEditorEvent
    data class TextResult(val text: String?) : ChannelEditorEvent
    data class NumberResult(val value: Int?) : ChannelEditorEvent
    data class Tick(val nowMs: Long) : ChannelEditorEvent
}

/** What a bulk write applies to. Keys are stable keys; AllOf re-runs the list query (same access) inside the write. */
sealed interface ChannelTarget {
    data class Keys(val keys: Set<String>) : ChannelTarget
    data class AllOf(val list: EditorList, val filter: EditorFilter, val except: Set<String>) : ChannelTarget
}
sealed interface GroupTarget {
    data class Keys(val keys: Set<String>) : GroupTarget
    data class AllOf(val showHidden: Boolean, val except: Set<String>) : GroupTarget
}

sealed interface EditorWrite {
    val playlistId: Long
    data class HideChannels(override val playlistId: Long, val target: ChannelTarget, val hidden: Boolean) : EditorWrite
    data class HideGroups(override val playlistId: Long, val target: GroupTarget, val hidden: Boolean) : EditorWrite
    data class LockChannels(override val playlistId: Long, val target: ChannelTarget, val locked: Boolean) : EditorWrite
    data class LockGroups(override val playlistId: Long, val target: GroupTarget, val locked: Boolean) : EditorWrite
    data class MoveChannels(override val playlistId: Long, val target: ChannelTarget, val toGroupKey: String) : EditorWrite
    data class ReorderChannels(override val playlistId: Long, val groupKey: String, val block: List<String>, val toIndex: Int) : EditorWrite
    data class ReorderGroups(override val playlistId: Long, val block: List<String>, val toIndex: Int) : EditorWrite
    data class SortChannels(override val playlistId: Long, val groupKey: String, val sort: ChannelSort) : EditorWrite
    data class SortGroups(override val playlistId: Long, val sort: GroupSort) : EditorWrite
    data class RenameChannel(override val playlistId: Long, val stableKey: String, val name: String?) : EditorWrite
    data class RenameGroup(override val playlistId: Long, val groupKey: String, val name: String?) : EditorWrite
    data class SetNumber(override val playlistId: Long, val stableKey: String, val number: Int?) : EditorWrite
    data class Favorite(override val playlistId: Long, val target: ChannelTarget, val on: Boolean) : EditorWrite
    data class ResetChannels(override val playlistId: Long, val target: ChannelTarget) : EditorWrite
    data class ResetGroups(override val playlistId: Long, val target: GroupTarget) : EditorWrite
}
sealed interface WriteResult {
    data class Done(val changed: Int, val skippedByRule: Int = 0, val numberClash: String? = null) : WriteResult
    data class Failed(val reason: WriteFailure) : WriteResult
}
/** STORAGE: SQLite error or full disk. GONE: every target left the library (refresh). REFUSED: refusesLowering() said no. BUSY: SQLITE_BUSY after one retry. */
enum class WriteFailure { STORAGE, GONE, REFUSED, BUSY }

interface ChannelEditorEffects {
    fun loadGroups(playlistId: Long, showHidden: Boolean, keepKey: String?)          // → Groups, then GroupRows for page 0
    fun loadGroupRows(version: Long, from: Int, to: Int)
    fun loadChannels(playlistId: Long, list: EditorList, filter: EditorFilter, keepKey: String?) // → Channels, ChannelRows
    fun loadChannelRows(version: Long, from: Int, to: Int)
    fun summarize(token: Long, playlistId: Long, target: ChannelTarget)               // → Summary (IO, one COUNT/SUM)
    fun locate(playlistId: Long, list: EditorList, filter: EditorFilter, number: Int) // → Located
    fun write(write: EditorWrite)                                                     // → Written (FIFO, app scope)
    fun editText(label: EditorText, initial: String, allowEmpty: Boolean)             // → TextResult (SettingsShellLink.editText)
    fun editNumber(label: EditorText, value: Int?)                                    // → NumberResult
    fun requestPin()                                                                  // → PinResult
    fun announce(text: String)
}

interface ChannelEditorMachine {
    val ui: kotlinx.coroutines.flow.StateFlow<ChannelEditorUiState>
    /** The next time the machine needs a Tick (notice fade, group-follow debounce, digit timeout); Long.MAX_VALUE = none. */
    val nextDeadlineMs: kotlinx.coroutines.flow.StateFlow<Long>
    fun open(playlistId: Long, groupKey: String?)
    /** False = not consumed (BACK at the ladder's bottom, OPEN_GUIDE, EXIT): the settings shell handles it. */
    fun act(action: app.thebox.core.guide.GuideEvent.Act): Boolean
    fun dispatch(event: ChannelEditorEvent)
    fun close()
}
fun interface ChannelEditorMachineFactory { fun create(effects: ChannelEditorEffects, texts: EditorTexts, nowMs: () -> Long): ChannelEditorMachine }
```

Machine rules:
- **Stale events.** A `Groups` / `GroupRows` / `Channels` / `ChannelRows` / `Summary` event whose version or token is
  not the current one is dropped (for example, a late page after a group change).
- **Deadlines.** `nextDeadlineMs` covers the notice fade (`NOTICE_MS`), the group-follow debounce
  (`FOLLOW_DEBOUNCE_MS` 150) and the digit timeout (1,500 ms). The view model ticks it from the existing ticker, as it
  does for the settings machine.
- **Write queue (FIFO).**
  - Each action snapshots its target when it runs and appends one `EditorWrite`. Writes run one at a time, in order,
    and are never merged.
  - The queue is bounded at 8. While it is full, a new action is refused with "Still saving. Try again in a moment."
  - The view model runs writes in the `@ApplicationScope` coroutine scope, so leaving the editor never cancels a queued
    write.
  - After `Written(Done)`, the machine clears the ticks the write acted on. Ticks made meanwhile survive.
  - The machine then asks for a reload with `keepKey` = the focused row's key. The view model finds that key's new
    index on IO and returns it as `cursorIndex`. If the row left the filter (Hide under "Shown only"), it returns null
    and the index is kept.
- **Menu summary.**
  - With one focused row and no ticks, the menu is built from that row at once.
  - Otherwise the menu opens with `loading = true` and `summarize(token, …)` runs. Its `Summary` fills the lines: Hide
    vs Show vs both, the rule-hidden count, Lock vs Unlock, Favorites add vs remove, and the enabled/disabled state of
    Unlock (`lockedByGroup`) and Move (`fromLockedGroup` needs the PIN).
- **List changes.** Changing the list or the filter clears `channelSelection` (notice "N unselected"). Toggling
  `showHiddenGroups` clears `groupSelection`.
- **Access.** `Access` events come from `ParentalSession.state`. A change reloads both panes, because E7 and
  `lockedOut` depend on it.
- **Playlist gone.** `PlaylistGone` (the playlist row was deleted) sets `gone`; only BACK works then.

### 3.4 Repository (core interface, data implementation)

`core/src/main/kotlin/app/thebox/core/editor/ChannelEditorRepository.kt`:

```kotlin
interface ChannelEditorRepository {
    class GroupList(val keys: List<String>, val hidden: Int)          // groups are few (typically < 2,000 short strings)
    class ChannelList(val ids: LongArray, val hidden: Int, val lockedOut: Int, val listLocked: Boolean)

    suspend fun playlistExists(playlistId: Long): Boolean
    /** Ordered LIVE group keys (user order, then provider order); hidden groups included when [showHidden]. */
    suspend fun groups(playlistId: Long, showHidden: Boolean, access: ParentalAccess): GroupList
    suspend fun groupRows(playlistId: Long, keys: List<String>, access: ParentalAccess): List<EditorGroupRow>
    /** Ordered process-local channel row ids of a list (the guide's LongArray approach, GuideQueries.lineup). */
    suspend fun channels(playlistId: Long, list: EditorList, filter: EditorFilter, access: ParentalAccess): ChannelList
    suspend fun channelRows(ids: LongArray, from: Int, to: Int): List<EditorChannelRow?>
    suspend fun indexOf(ids: LongArray, playlistId: Long, stableKey: String): Int?
    suspend fun summarize(playlistId: Long, target: ChannelTarget, access: ParentalAccess): SelectionSummary
    suspend fun locate(playlistId: Long, list: EditorList, filter: EditorFilter, access: ParentalAccess, number: Int): Int?
    suspend fun write(write: EditorWrite, access: ParentalAccess): WriteResult
    /** Emits on changes of channel, channel_group, group_prefs, favourite, hide_rule, playlist, and of channel_prefs filtered through the guide's prefs signature (§3.7). */
    fun changes(playlistId: Long): kotlinx.coroutines.flow.Flow<Unit>
}
```

It is implemented by `data/.../editor/ChannelEditorRepositoryImpl.kt` over `LibraryDatabase`, with raw statements (as
`GuideQueries`), and bound in a new `data/.../di/EditorModule.kt`.

### 3.5 Writes: where each goes, transactions, order rules, locks

**Transactions.**
- Each action is **one `LibraryDatabase.writeTransaction`** on Room's single writer connection. That already
  serialises it against refresh merge batches (`RoomMergeSink.begin` / `apply` / `finish`).
- **No `ImportMutex`.** It is also held for whole EPG builds and patches (`EpgRepository.kt:311`, `:341`), which run
  for minutes right after an import, exactly when users open the editor.
- It is not needed for correctness either: user data is keyed by `stable_key`, and `UserDataRemapper` moves prefs
  written mid-merge to renamed keys in `finish`. The guide's own hide and group writes (`GuideLibraryImpl.kt:160-178`)
  take no lock today.
- An `SQLITE_BUSY` past the driver's busy timeout is retried once, then reported as `WriteFailure.BUSY`.
- A write whose every target key is gone from `channel` reports `GONE`.

| Write | Table.column | Path |
|---|---|---|
| Hide / Show channels | `channel_prefs.hidden` | Keys: `ChannelPrefsDao.upsertHidden` per key, batched in the one transaction. AllOf: `EditorDao.hideAll`, one `INSERT INTO channel_prefs(…) SELECT … FROM channel c <joins> WHERE <list predicate> AND c.stable_key NOT IN (…) ON CONFLICT(playlist_id, stable_key) DO UPDATE SET hidden = excluded.hidden, updated_at = excluded.updated_at`. The SELECT always has a `WHERE`, which SQLite's upsert-after-join parsing needs; the DAO doc says so |
| Hide / Show groups | `group_prefs.hidden` | `GroupDao.upsertGroupHidden` per key |
| Lock / Unlock channels | `channel_prefs.locked` | **`ParentalControls.setChannelsLocked(playlistId, target: ChannelTarget, access, locked)`** (new, bulk): `ParentalSessionImpl` resolves an AllOf to keys **inside its own write transaction** (through `EditorQueries.resolveKeys`, so they cannot go stale during a merge), writes one statement batch in that transaction, and refuses an unlock under `refusesLowering()` (`REFUSED`) |
| Lock / Unlock groups | `group_prefs.locked` | **`ParentalControls.setGroupsLocked(playlistId, groupKeys, locked)`** (new, bulk), with the same refusal as today's `setGroupLocked` |
| Reorder / sort channels | `channel_prefs.custom_sort_index` | `EditorDao.setChannelOrder(pid, groupKey, orderedKeys)`: a dense rewrite, batched with `forEachBound` in one transaction, over **every channel whose effective group is the group**: hidden, rule-hidden, moved-in, and missing ones (so a returning channel keeps its slot) |
| Reorder / sort groups | `group_prefs.custom_sort_index` | `GroupDao.setGroupOrder` over the full ordered key list (`GroupAdminDao.orderedKeys`, hidden included) |
| Provider order | clear `custom_sort_index` for the group's channels / every group | `EditorDao.clearChannelOrder(pid, groupKey)`, `EditorDao.clearGroupOrder(pid)` |
| Rename channel | `channel_prefs.custom_name` (empty or null = the provider's name) | `ChannelPrefsDao.upsertCustomName` (column-targeted, like `upsertHidden`) |
| Rename group | `group_prefs.custom_name` | **`GroupDao.upsertGroupCustomName`** (new, column-targeted; not the full-row `upsertPrefs`, which would clobber hidden/lock/order in a read-modify-write race) |
| Set number / Use the provider's number | `channel_prefs.custom_number` (null = provider) | `ChannelPrefsDao.upsertCustomNumber`, plus a clash lookup (`numberClash`). The guide's number entry already reads it (`ChannelDao.byNumber`) |
| Favorites | `favourite(list_id, playlist_id, stable_key, sort_index)` in the **default list** (`FavouritesRepository.defaultList()`, created inside the transaction if none exists) | add: append after `maxSortIndex`, in list order; remove: from the default list only (other lists are managed on the Favorites lists page) |
| Move to group | `channel_prefs.custom_group_key` (§3.6) + `custom_sort_index` | `ChannelPrefsDao.upsertCustomGroup` + the order rule below |
| Reset channels | clears `custom_name`, `custom_number`, `custom_group_key`, `hidden`, `locked` (→ null = automatic) | `EditorDao.resetChannels`; refused under `refusesLowering()` when any target has a lock |
| Reset groups | `GroupDao.deletePrefs` (hidden, lock, order, name) | refused under `refusesLowering()` when any target is locked |

**Order rules.**
- (a) **Moving into a group.**
  - If the target has a custom order (any member has a non-null `custom_sort_index`), the moved channels get
    `max + 1 … max + n`, in list order. So they go to the end.
  - If it has none, the target's provider order is first materialised densely (its `custom_sort_index` becomes
    0 … m − 1) and the moved channels get `m … m + n − 1`. So they also go to the end, which is what a user expects.
    The target then counts as custom-ordered. **Decision:** this first move freezes the group's provider order; a later
    provider re-sort is ignored, and new provider channels go to the end (null sorts last). Sort › Provider order
    undoes it.
- (b) **Moving back to the provider group** (a move, a Reset of a moved channel, or a purge, §3.6) writes
  `custom_group_key = NULL` and `custom_sort_index = NULL`, because the old index belonged to the other group.
  - With a custom order in the provider group, the channel sorts after the custom-ordered channels (at the end).
  - Without one, it returns to its provider position.
  - A move to the channel's own provider group always writes null, never its own key.
- (c) **Reset of a channel that was not moved does not touch `custom_sort_index`.** Order is a group property, with its
  own undo (Sort › Provider order).
- (d) **The guide's group Move up/down** (`GuideLibraryImpl.groupAction`) is changed to keep hidden groups' positions
  consistent. Today it rewrites the order from `groupDao.groups(…, unlocked = true)`, which leaves out hidden and
  rule-hidden groups, so their old indices collide. The new behaviour:
  - It takes the full list `GroupAdminDao.orderedKeys(pid, 0)` (hidden included) and the set of visible keys (the
    panel's list).
  - From the group's position, it walks in the move direction to the nearest **visible** key and swaps the two.
  - It rewrites the whole list with `setGroupOrder`.
  - Hidden groups keep their slots. All three writers (editor, Groups page, guide) now rewrite the same key set.
- (e) **Returning groups.** `GroupAdminDao.orderedKeys` leaves out missing groups, so a group the provider drops and
  sends again would come back with a stale index that can tie with another group's. **Decision:**
  `RoomMergeSink.begin`'s group upsert, when it sets `missing_since` back to NULL on a group that was missing, also
  clears that group's `group_prefs.custom_sort_index` in the same statement batch. The group rejoins after the
  custom-ordered groups, at the end.
  - **Amended (data review, 2026-10-18):** the index is cleared **only when another group of the playlist and kind
    holds the same index** (a real tie). Many Xtream panels leave out an empty category for one refresh; clearing on
    every return would send that group to the end of the user's order after each blip. Without a tie the group keeps
    its slot. `GroupPurgeClearsMovesTest.aReturningGroupWithNoTieKeepsItsPosition`.
- (f) **Sorts.**
  - A name sort uses a `java.text.Collator` for the default locale at SECONDARY strength, on IO, over the group's
    `(key, effective name)` list. SQLite `NOCASE` is ASCII-only, and names like "FR | Général" must sort correctly.
  - A number sort uses the number the guide shows (custom, else the numbering mode), with no-number channels last.
  - A group sort uses the effective group name.

**Reset scope, precisely.**
- Channel Reset returns what the editor changed per channel: name, number, group move, hidden and lock.
- It keeps Favorites, the custom logo, the guide shift, playback preferences and watch history.
- It never touches `rule_hidden`.
- Group Reset clears the group's own prefs (hidden, lock, order, name). It does **not** return channels moved into or
  out of the group.
- Both ask for the PIN when they would clear a lock and a PIN is set (§2.6), and the data layer refuses them under
  `refusesLowering()`.

**Moving out of a locked group** (when `fromLockedGroup > 0` in the summary) asks for the PIN, and the data layer
refuses it under `refusesLowering()`. Moving into a locked group is always allowed.
- **Amended (data review, 2026-10-18):** the data layer compares each mover's lock **before and after** the move
  (own lock or group lock, with the target's `group_prefs.locked` / `is_adult`) and refuses when a channel locked now
  would not be locked after. This also covers an adult-detected channel moved into a group whose lock was explicitly
  turned off (`gp.locked = 0`), and it no longer refuses a move between two locked groups.

### 3.6 Move to group: the schema change (library.db v4 → v5; owner ruling Q1)

**Schema.**
- `ChannelPrefsEntity` gains `@ColumnInfo(name = "custom_group_key") val customGroupKey: String? = null`. The value is a
  LIVE `group_key` of the same playlist: `xcat:<id>` or `g:<name>`, both refresh-stable
  (`core/.../identity/GroupKeys.kt`).
- It also gains `@Index(value = ["playlist_id", "custom_group_key"], name = "index_channel_prefs_group")`. The name and
  columns must match the migration exactly, for Room's validation.
- `Migrations.MIGRATION_4_5` is hand-written, with no rebuild: `ALTER TABLE channel_prefs ADD COLUMN custom_group_key
  TEXT` plus `CREATE INDEX IF NOT EXISTS index_channel_prefs_group ON channel_prefs(playlist_id, custom_group_key)`.
  Every existing row gets NULL, so behaviour does not change.
- `LibraryDatabase.VERSION = 5`, and `data/schemas/app.thebox.data.db.LibraryDatabase/5.json` is exported.
- There is no downgrade: an older APK installed over v5 crashes on open, as it does for v4 today. The release notes
  say so.

**Effective group, with fallback (review 2 ruling a).** The effective group is the target **whenever its
`channel_group` row exists**, including during the target's grace period (`missing_since` set). In that period the
moved channels behave like the group's own channels: the panel hides the missing group, and they keep its lock and
hidden state, so nothing lowers protection without the PIN (C-B2). Only when the target row no longer exists does the
effective group fall back to the provider group. The purge clearing (a) and the restorer (c) below remove such keys
anyway. The constants live in `ChannelDao`'s companion, in pieces, so that every query shape can use them without
joining an alias twice:

```kotlin
/** The provider's group (refresh-owned membership). Only the allowlisted readers use it alone (table below). */
const val PROVIDER_GROUP_JOIN = "LEFT JOIN channel_group g0 ON g0.id = c.group_id\n"
/** The channel's prefs (for queries that do not already drive from channel_prefs cp). */
const val CP_JOIN = "LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key\n"
/** Needs g0 and cp in scope: the move target gt (any existing row, missing or not), the effective group g, its prefs gp. */
const val GROUP_TAIL = """
LEFT JOIN channel_group gt ON gt.playlist_id = c.playlist_id AND gt.kind = 0 AND gt.group_key = cp.custom_group_key
LEFT JOIN channel_group g  ON g.id = COALESCE(gt.id, g0.id)
LEFT JOIN group_prefs gp   ON gp.playlist_id = c.playlist_id AND gp.kind = g.kind AND gp.group_key = g.group_key
"""
const val EFFECTIVE_GROUP_TAIL = CP_JOIN + GROUP_TAIL
const val EFFECTIVE_GROUP_JOINS = PROVIDER_GROUP_JOIN + EFFECTIVE_GROUP_TAIL
```

The existing `HIDDEN` / `PARENTAL` / `PARENTAL_ACCESS` predicates and `TuneGuardImpl`'s lock clauses already reference
`g`, `cp` and `gp`. They now see the effective group. So moving into "Adult" locks a channel, and moving out of it
unlocks it, everywhere at once: lineups, the tune guard and Open anyway.

**Every reader of `channel.group_id`** (`grep -rn group_id data/src/main`, 2026-10-18; entity declarations excluded):

| # | Where | What it is | v5 |
|---|---|---|---|
| 1 | `db/dao/ChannelDao.kt:83` `browseGroup` | **M1-only** group browse (no production caller outside `ChannelRepository.kt:41`) | **effective**: filter `g.id = :groupId` (the effective group's id; it cannot use the `group_id` index, accepted for an M1-only reader) |
| 2 | `ChannelDao.kt:162` `search` | channel search, parental predicate | **effective** |
| 3 | `ChannelDao.kt:186` `matchedWithoutData` | Fix guide data, parental predicate | **effective** |
| 4 | `ChannelDao.kt:239` `UNMATCHED_HEAD` | Fix guide data `unmatched` / `unmatchedAccess` | **effective** |
| 5 | `ChannelDao.kt:256` `SELECT_ROW` | `browseAll` (**M1-only**, `ChannelRepository.kt:44`), `atPosition`, `bySortIndex*`, `byNumber*`, `row` | **effective** |
| 6 | `guide/GuideQueries.kt:344` `LINEUP_JOINS` | every guide lineup, `FAV_FROM`, search, refs | **effective** |
| 7 | `GuideQueries.kt:355` `RECENT_FROM` | Recently watched | **effective** |
| 8 | `parental/TuneGuardImpl.kt:119` `LockedChannelIndex.SQL` | the guard every tune goes through; `groupOf` for Open anyway | **effective**: `g.group_key` becomes the effective key, and the `WHERE` lock prefilter uses `g.is_adult` of the effective group |
| 9 | `db/dao/FavouriteDao.kt:54` `channels` | favourites lineup (M1 API), parental predicate | **effective** |
| 10 | `db/dao/HiddenChannelsDao.kt:41` `entries` | Hidden channels page, group name | **effective** (the group it is shown in) |
| 11 | `db/dao/GroupDao.kt:36` `groups` count | Groups panel (M1 signature), guide Move up/down | **effective**: count = native not moved away + moved in |
| 12 | `GroupDao.kt:56` `panelGroups` count | Groups panel, player group column | **effective** |
| 13 | `db/dao/GroupAdminDao.kt:37` `entries` count | Groups page | **effective** |
| 14 | `rules/ChannelsPagesImpl.kt:136` | "N channels hidden by rules" summary | **effective** |
| 15 | `rules/RuleHiddenPass.kt:72` | rule preview "Matches N groups, M channels" | **effective**: count channels whose effective group is matched |
| 16 | `guide/DisplayNames.kt:128` | prefix stripping by the provider's group name | **provider, on purpose** (the name rule follows what the provider sent) |
| 17 | `setup/PlaylistSettingsRepository.kt:286` | Groups review after import | **provider, on purpose** (counts what the provider sent) |
| 18 | `repo/RoomExistingChannels.kt:21` | merge: existing rows | **provider, on purpose** (refresh-owned) |
| 19 | `repo/RoomMergeSink.kt:233` | group purge | **provider, on purpose**, plus the new clearing below |
| 20 | `repo/RoomMergeSink.kt:284` | insert column list | **provider, on purpose** |

Counts (11–15) use one form: `SELECT COUNT(*) FROM channel c <EFFECTIVE_GROUP_JOINS> WHERE c.missing_since IS NULL AND
g.id = <group id>`. For the per-group subqueries of 11–13, this becomes a grouped count joined back by `g.id`, so it
runs one query, not one per group.

**Guard test.** `ProviderGroupJoinGuardTest` (`data/src/test/kotlin/app/thebox/data/db/`) scans
`data/src/main/**/*.kt` for `= c.group_id`, `c.group_id =` and `group_id IN`. It fails on any match outside the
allowlist: `ChannelDao.PROVIDER_GROUP_JOIN`, rows 16–20, and the first half of the group lineup (which drives from
`g0`, below). It also checks that every query joining `channel_group g` uses either `EFFECTIVE_GROUP_JOINS` or
`PROVIDER_GROUP_JOIN + GROUP_TAIL`, and that `GROUP_TAIL` never follows a second `channel_prefs cp` join (the
`RECENT_FROM` shape).

**Group lineup** (`GuideSource.Group`, and the editor's Group list): a compound select whose ORDER BY uses only result
columns, and whose first half drives from the provider group's identity index.

```sql
SELECT id FROM (
  SELECT c.id AS id, (cp.custom_sort_index IS NULL) AS o1, cp.custom_sort_index AS o2, c.sort_index AS o3
  FROM channel_group g0
  JOIN channel c ON c.group_id = g0.id
  JOIN playlist p ON p.id = c.playlist_id AND p.enabled = 1
  <EFFECTIVE_GROUP_TAIL>
  WHERE g0.playlist_id = ?2 AND g0.kind = 0 AND g0.group_key = ?3
    AND (cp.custom_group_key IS NULL OR gt.id IS NULL)          -- not moved away (or the target is gone)
    AND <LINEUP_BASE> <PARENTAL_RAW>
  UNION ALL
  SELECT c.id, (cp.custom_sort_index IS NULL), cp.custom_sort_index, c.sort_index
  FROM channel_prefs cpm
  JOIN channel c ON c.playlist_id = cpm.playlist_id AND c.stable_key = cpm.stable_key
  JOIN playlist p ON p.id = c.playlist_id AND p.enabled = 1
  <PROVIDER_GROUP_JOIN><EFFECTIVE_GROUP_TAIL>
  WHERE cpm.playlist_id = ?2 AND cpm.custom_group_key = ?3 AND gt.id IS NOT NULL
    AND <LINEUP_BASE> <PARENTAL_RAW>
) ORDER BY o1, o2, o3
```

`?1` (access) is bound once and used in both halves.

`GuideQueries.Clause` gains a full-SQL form, `Clause.Sql(idsSql, bind)`, for this union. `lineup` then runs:
- the ids from `idsSql`;
- the archive bound as `SELECT <ARCHIVE_DAYS> FROM channel c JOIN playlist p ON p.id = c.playlist_id WHERE c.id IN
  (SELECT id FROM (<union without ORDER BY>))`.

The three existing variants keep their current `from` / `where` / `order` form, with `LINEUP_JOINS` rebuilt as
`PROVIDER_GROUP_JOIN + EFFECTIVE_GROUP_TAIL`. `RECENT_FROM` already drives `FROM channel_prefs cp`, so it uses
`JOIN channel c … JOIN playlist p … PROVIDER_GROUP_JOIN + GROUP_TAIL`, without `CP_JOIN` (joining `cp` twice would be a
duplicate alias).

**Plan assertions** (`GroupLineupPlanTest`, on device in `:app` androidTest, on the bundled driver, which is
production; the Robolectric data tests check results only):
- half 1 contains `SEARCH g0 USING INDEX index_channel_group_identity (playlist_id=? AND kind=? AND group_key=?)` and
  `SEARCH c USING INDEX index_channel_group (group_id=?)` (or `index_channel_browse` with `group_id=?`);
- half 2 contains `SEARCH cpm USING INDEX index_channel_prefs_group (playlist_id=? AND custom_group_key=?)`;
- neither half contains `SCAN c`, `SCAN channel` or `SCAN cpm`.

**Purge and dangling keys.**
- (a) `RoomMergeSink.updateGroups` (`:231-235`): in the same batch, **before** its `DELETE FROM channel_group`, it runs
  `UPDATE channel_prefs SET custom_group_key = NULL, custom_sort_index = NULL WHERE playlist_id = ? AND
  custom_group_key IN (SELECT group_key FROM channel_group WHERE <the same delete predicate>)`. The moved-in channels
  return to their provider groups (§3.5 rule b).
  - The delete predicate itself is unchanged (`NOT EXISTS` provider members). A provider group that keeps only
    moved-in channels is therefore deleted when the provider has dropped it past the grace period, and its moved-in
    channels go home.
- (b) Readers fail safe by construction: `gt` matches only an existing row, so a dangling key or a hand-deleted group
  falls back to the provider group. A target inside its grace period (`missing_since` set) is still the effective
  group: its moved channels stay locked and hidden with it (review 2 ruling a), and show again when the provider sends
  the group again.
- (c) `BackupRestorer` drops a restored `custom_group_key` whose target key has no `channel_group` row in the device's
  playlist, and reports nothing; the channel stays in its provider group.
  - **Amended (data review, 2026-10-18):** the key is dropped only when the playlist **already has LIVE groups** and
    none has the key. A playlist the restore has just added has no `channel_group` rows until its first refresh, so
    its moves are kept: readers fall back through `gt` until the target arrives, then the move applies. A restore onto
    a new box is the main reason for a backup. When a key is dropped, the record's `custom_sort_index` is dropped with
    it (it indexed the target's order, rule b).

**Backup.**
- The column list is `MergePlanner.CHANNEL_PREF_COLUMNS` in `:core` (`core/.../backup/MergePlanner.kt:82`). Add
  `"custom_group_key"` there, and to `BackupExporter.USER_PREF_PREDICATE` (`:165`). The export (`:110`), the restore
  filter (`BackupRestorer.kt:283`) and `clearUserRows` (`:377-381`) all follow the set.
- A v4 backup has no field: merge mode keeps the device's value, and replace mode nulls it.
- A v5 backup restored on an older build is filtered by that build's set.

**Guide signature.** `GuideQueries.PREFS_SIGNATURE_SELECT` adds `custom_group_key` to its select list **and** to its
`WHERE … OR custom_group_key IS NOT NULL`, and `prefsSignature` adds its hash step. Otherwise a channel whose only pref
is a move is not hashed, and the guide never re-runs the lineup.

### 3.7 How the guide sees the edits, and what All does (owner ruling Q2)

**Live updates: no new mechanism.**
- The guide's `GuideLibraryImpl.lineup` and `sources` re-query on Room's invalidation tracker over the lineup tables.
  `channel_prefs` reaches them through `GuideQueries.prefsSignature` (hidden, locked, custom name, number, logo, sort,
  shift, and from v5 the custom group).
- One editor action is one transaction, so one invalidation. It can still mean two lineup queries when a write touches
  `group_prefs` and `channel_prefs` together (`GuideLibraryImpl.trigger` merges a table flow and the signature flow).
  `versionOf` dedupes the result, so the guide re-emits at most once. The guide keeps the focused index on hide (H-S1).
- The editor's own `changes(pid)` uses the same tracker on `channel`, `channel_group`, `group_prefs`, `favourite`,
  `hide_rule` and `playlist`, plus **`channel_prefs` through `GuideQueries.prefsSignature(withRecentCount = false)`**.
  Watch-history and learned-playback writes from the channel playing behind Settings (`last_watched_at`,
  `playback_flags`, `fps_milli`) therefore never re-run the editor's list queries. Edits made elsewhere (the group menu,
  Channel options, a refresh merge, a rule pass) do appear in an open editor.

**All follows the user's order (ruled).** `GuideSource.All` and the editor's All list order by:
1. the effective group's position: `g.id IS NULL`, `gp.custom_sort_index IS NULL`, `gp.custom_sort_index`,
   `g.sort_index`, `g.id`;
2. then the channel's position: `cp.custom_sort_index IS NULL`, `cp.custom_sort_index`, `c.sort_index`.

This is not a compound select, so expressions are fine. It is a temp B-tree over the playlist's lineup on every
re-query, measured in §4. Zap order in All follows.

**Numbers in All (ruled here, v1).** Channel numbers do **not** follow the new order.
- A channel shows its custom number if one is set.
- Otherwise it shows the number the playlist's numbering mode gives (Provider; Playlist order / Start at N, derived from
  `c.sort_index`: `GuideLibraryImpl.kt:135-146`, `bySortIndexAccess`).
- So after a reorder, numbers in All are no longer ascending, and number entry still finds each channel by its number.
- To renumber, users use **Set number…**. The Channels and groups help line, and the acceptance list, say so.
- Numbering by All position would need a lineup-position lookup on the number-entry path. It is recorded as a v1.1
  candidate, not done here.

---

## 4. Performance (thousands of channels)

1. **Ids, not rows.** A list is an ordered `LongArray` of `channel.id`: 8 bytes a row, so 20,000 channels are 160 KB. It
   is built by one raw statement, like `GuideQueries.lineup` (no entity mapping, no boxing).
   - Row content is fetched in pages of 32, with `WHERE c.id IN (…)` (the guide's `BLOCK_SELECT` shape plus prefs,
     effective group and favorite columns).
   - Ids are process-local and never persisted or written. Writes use the stable keys the rows carry.
2. **Bounded window.** The machine keeps at most `EDITOR_PAGES_KEPT` (6) pages per pane (192 rows). It loads
   `[cursor − 32, cursor + 64)` and evicts the rest, as the settings paged list does.
   - The UI is a `LazyColumn` with `items(count)` and `PlaceholderRow` for unloaded indices; rows off screen are never
     composed.
   - The two panes are siblings, not nested lists.
3. **Group follow is debounced** (150 ms, a machine deadline) and versioned. Holding ↓ through 60 groups loads one
   channel list, and a late page for an old group is dropped.
4. **Nothing O(n) on the main thread.**
   - Select all is symbolic (`AllExcept`).
   - The menu summary is one `SELECT COUNT(*), SUM(CASE …)` on IO, over the write's predicate.
   - Cursor restore by key after a sort, move or reload is `indexOf` on IO over the id array.
   - Number jump is `locate` on IO.
   - Explicit ticks are bounded by key presses. The machine keeps `key → index` for them, never the rows.
5. **Bulk writes.**
   - An AllOf write re-runs the list's id query inside its transaction and applies to `ids − except`, in batches of 500
     (`forEachBound`). Hide/show uses the single `INSERT … SELECT … ON CONFLICT DO UPDATE`.
   - Locks make one bulk `ParentalControls` call, which resolves an AllOf's keys inside its own transaction.
6. **Move mode is display-only.**
   - The block's position is an index remap in state, and the lifted rows are carried with it, so no query runs per
     step.
   - The drop streams the group's keys in order: one group, not the playlist. A 5,000-channel group is about 0.5 MB,
     transient, on IO.
   - It splices the block and rewrites `custom_sort_index` in one transaction. Sorts do the same with a `Collator`.
   - No window functions: they are fine on the bundled driver, but the Robolectric tests run `AndroidSQLiteDriver`.
7. **Groups** are paged the same way over `groups(pid)`: a short `List<String>`, typically < 2,000. The picker shares
   that cache.
8. **Targets.** `EditorPerfTest` measures them on the API 30 emulator (20,000 synthetic channels, 600 groups) and logs
   them as **emulator** numbers. The same test is added to the open device gates (Onn 4K, Fire TV Stick 4K). Budgets:

   | Measure | Budget |
   |---|---|
   | Channel list ids P90, All | ≤ 40 ms |
   | Channel list ids P90, one group (the union) | ≤ 15 ms |
   | Page of 32 rows | ≤ 10 ms |
   | Group switch to first painted rows | ≤ 120 ms |
   | Summary over 15,000 rows | ≤ 60 ms |
   | Bulk hide of 5,000 | ≤ 400 ms |
   | Reorder drop in a 3,000-channel group | ≤ 250 ms |
   | **Guide All lineup ids P90** with the Q2 order | ≤ 50 ms (guide side) |
   | **Groups panel counts P90** (effective grouped count; 20,000 channels / 600 groups, on every `sources` re-emit) | ≤ 40 ms (guide side) |
   | Editor retained heap | ≤ 2 MB |

   - **JVM tolerance (data review, 2026-10-18).** The `:data` JVM run of `EditorPerfTest` fails only at **8×** a
     budget, not 3×: it shares the machine with four other Gradle suites and the review agents, and under that load All
     ids reached 174–407 ms (54 ms alone). The device runs above are the gate; the JVM run only catches gross
     regressions.
   - **Follow-up, measure on the API 30 emulator first: the no-move variant of All.** When the playlist has no move
     (`NOT EXISTS (SELECT 1 FROM channel_prefs WHERE playlist_id = ? AND custom_group_key IS NOT NULL)`, one probe of
     `index_channel_prefs_group`), All could run with `LEFT JOIN channel_group g ON g.id = c.group_id` and no `g0`/`gt`,
     saving two lookups per row. Adopt it only if the emulator misses the All budgets; the guard test would allowlist
     it as a named constant.

---

## 5. Texts

### 5.1 Keys

The keys live in `core/.../editor/EditorTexts.kt`:
- `enum class EditorText`;
- `interface EditorTexts { fun get(key: EditorText, vararg args: Any): String; fun count(key: EditorText, n: Int,
  vararg args: Any): String }`.

`:ui`'s `ResourceEditorTexts` maps each key, in an exhaustive `when`:
- `get` keys to `R.string.editor_<key lower>` in `strings_editor.xml`;
- **(P)** keys to `R.plurals.editor_<key lower>`, through `count`.

JVM tests use `EditorTexts.KEYS`, which returns the key name and its arguments. The texts are American English
throughout ("Favorites", "color"). Every key below goes in at commit 0.

| Key | English |
|---|---|
| TITLE | Channel editor |
| ROW_LABEL (BackupText.CHANNELS_EDITOR) | Channel editor |
| ROW_VALUE (BackupText.CHANNELS_EDITOR_VALUE) | Hide, sort and move channels |
| GROUPS_TITLE | Groups |
| GROUPS_COUNT (P) | %1$d group · %2$d hidden / %1$d groups · %2$d hidden |
| GROUPS_COUNT_NONE (P) (review N2: when nothing is hidden) | %1$d group / %1$d groups |
| ALL_CHANNELS | All channels |
| FIND_RESULTS | Results for “%1$s” |
| CHANNELS_COUNT (P) | %1$d channel · %2$d hidden / %1$d channels · %2$d hidden |
| CHANNELS_COUNT_NONE (P) (review N2) | %1$d channel / %1$d channels |
| GROUP_VALUE_HIDDEN (P) (review N1: a group row's value) | %1$d · %2$d hidden |
| SELECTED_COUNT (P) | %1$d selected |
| SAVING | Saving… |
| DETAIL_MOVED | from %1$s |
| DETAIL_RENAMED | was %1$s |
| DETAIL_RULE | Hidden by the rule “%1$s” |
| LOCKED_NOTE | Locked channels are not shown. Press OK to enter your PIN. |
| TB_ACTIONS / TB_SORT / TB_FIND | Actions / Sort… / Find… |
| TB_SHOW | Show: %1$s |
| TB_HIDDEN_GROUPS_ON / TB_HIDDEN_GROUPS_OFF | Hidden groups: Shown / Hidden groups: Not shown |
| FILTER_ALL / FILTER_SHOWN / FILTER_HIDDEN | All / Shown only / Hidden only |
| SORT_NAME_AZ / SORT_NAME_ZA / SORT_NUMBER / SORT_PROVIDER | Name A–Z / Name Z–A / Number / Provider order |
| MENU_TITLE_CHANNELS (P) | %1$d channel selected / %1$d channels selected |
| MENU_TITLE_GROUPS (P) | %1$d group selected / %1$d groups selected |
| A_HIDE / A_SHOW | Hide / Show |
| A_MOVE_TO_GROUP | Move to group… |
| A_REORDER | Reorder |
| A_ADD_FAVORITE / A_REMOVE_FAVORITE | Add to Favorites / Remove from Favorites |
| A_LOCK / A_UNLOCK | Lock / Unlock |
| A_RENAME / A_SET_NUMBER / A_CLEAR_NUMBER | Rename… / Set number… / Use the provider's number |
| A_SELECT_ALL | Select all in this list |
| A_FIND / A_SHOW_FILTER / A_SORT / A_SORT_GROUPS | Find… / Show… / Sort… / Sort groups… |
| A_EDIT_CHANNELS / A_DROP_RESULTS | Edit channels / Drop results |
| A_RESET | Reset to provider settings… |
| PICKER_TITLE (P) | Move %1$d channel to / Move %1$d channels to |
| PICKER_HERE | Already here |
| PICKER_HIDDEN_DETAIL | Hidden: the channels won't show until you show the group |
| MOVE_HEADER | Moving %1$d · position %2$d of %3$d |
| EDIT_RENAME_CHANNEL / EDIT_RENAME_GROUP | Channel name (leave empty for the provider's name) / Group name (leave empty for the provider's name) |
| EDIT_NUMBER / EDIT_FIND | Channel number / Find channels |
| N_HIDDEN / N_SHOWN (P) | %1$d channel hidden / %1$d channels hidden; %1$d shown |
| N_GROUPS_HIDDEN / N_GROUPS_SHOWN (P) | %1$d group hidden / %1$d groups hidden; … shown |
| N_SKIPPED_RULE (P) | %1$d stays hidden by a rule. Change it in Hide rules. / %1$d stay hidden by a rule. Change it in Hide rules. (after N_SHOWN, joined with " · " on one notice line, review S8) |
| N_MOVED (P) | Moved %1$d channel to %2$s / Moved %1$d channels to %2$s |
| N_ORDER_SAVED / N_SORTED | Order saved / Sorted by %1$s |
| N_RENAMED / N_NAME_RESET | Renamed / Name reset to %1$s |
| N_NUMBER_SET / N_NUMBER_CLEARED | Number %1$d set / Back to the provider's number |
| N_NUMBER_CLASH | Number %1$d is also on %2$s. Number entry opens the first one. |
| N_FAVORITE_ADDED / N_FAVORITE_REMOVED (P) | Added %1$d to Favorites / Removed %1$d from Favorites |
| N_LOCKED (P) / N_LOCKED_NO_PIN | Locked %1$d / Locked. Set a PIN in Parental controls to protect it. |
| N_UNLOCKED (P) | Unlocked %1$d |
| N_RESET (P) | Reset %1$d channel / Reset %1$d channels |
| N_GROUPS_RESET (P) | Reset %1$d group / Reset %1$d groups |
| N_UNSELECTED (P) | %1$d channel unselected / %1$d channels unselected |
| N_QUEUE_FULL | Still saving. Try again in a moment. |
| N_NO_NUMBER | No channel %1$d in this list |
| C_SORT_TITLE / C_SORT_BODY | Sort %1$s? / Your own order for this group is replaced. You can reorder again at any time. |
| C_PROVIDER_TITLE / C_PROVIDER_BODY | Go back to the provider's order? / Your own order for %1$s is removed. |
| C_SORT_GROUPS_TITLE / C_SORT_GROUPS_BODY (review S8: a `SortGroups` confirm) | Sort groups? / Your own group order is replaced. You can reorder again at any time. |
| C_PROVIDER_GROUPS_BODY (review S8: `SortGroups(PROVIDER)`, with C_PROVIDER_TITLE) | Your own group order is removed. |
| C_RESET_TITLE (P) | Reset %1$d channel? / Reset %1$d channels? |
| C_RESET_BODY | Names, numbers, group moves, hiding and locks go back to the provider's. Your order, Favorites and playback settings stay. |
| C_RESET_GROUPS_TITLE (P) | Reset %1$d group? / Reset %1$d groups? |
| C_RESET_GROUPS_BODY | Names, order, hiding and locks go back to the provider's. Channels you moved in or out stay where they are. |
| C_CONFIRM_SORT / C_CONFIRM_RESET / CANCEL | Sort / Reset / Cancel |
| FOOT_BROWSE / FOOT_SELECT / FOOT_CHANNELS / FOOT_GROUPS / FOOT_ACTIONS / FOOT_DONE / FOOT_CLEAR / FOOT_MOVE / FOOT_DROP / FOOT_CANCEL / FOOT_GUIDE | Browse / Select / Channels / Groups / Actions / Done / Clear selection / Move / Drop / Cancel / Back to the guide |

### 5.2 Empty, disabled and error states (reason + next step)

| State | Key and text |
|---|---|
| Playlist has no channels | E_NO_CHANNELS "This playlist has no channels yet. Refresh it in Settings › Playlists." |
| Playlist removed while open | E_PLAYLIST_GONE "This playlist was removed. Press Back to pick another one." |
| Group empty under a filter | E_EMPTY_HIDDEN "No hidden channels in %1$s. Set Show to All to see every channel." / E_EMPTY_SHOWN "Every channel in %1$s is hidden. Set Show to All or Hidden only." (one key per filter, no spliced words) |
| Group empty (provider) | E_EMPTY_GROUP "%1$s has no channels in the latest update. They come back with the next refresh, or you can hide the group." |
| Find found nothing | E_FIND_NONE "No channel matches “%1$s”. Check the spelling or try part of the name." |
| Locked group (E7) | E_LOCKED "%1$s is locked. Press OK to enter your PIN." |
| PIN refused or session relocked (`REFUSED`) | E_PIN_UNLOCK "Unlocking needs the PIN. Nothing was changed. Choose Unlock again and enter the PIN." · E_PIN_RESET "Reset clears locks, so it needs the PIN. Nothing was changed. Choose Reset again and enter the PIN." · E_PIN_MOVE "Moving channels out of a locked group needs the PIN. Nothing was changed. Choose Move to group again and enter the PIN." |
| Targets gone (`GONE`) | E_GONE "These channels were removed by the last playlist update. The list has been refreshed." |
| Load failed | E_LOAD "Couldn't read the channel list (storage error). Press OK to try again; if it keeps failing, restart the app." (shown in the failed pane only; OK retries from either pane, review S1) |
| Selection count failed (review S4) | E_SUMMARY "Couldn't count the selection (storage error). Open the menu again; if it keeps failing, restart the app." (a notice; the menu closes) |
| A list empty only because the PIN leaves channels out (review S9) | no empty text: the locked-out note row alone |
| Write failed (`STORAGE`) | E_WRITE "Couldn't save the change (storage full or an error). Your list is unchanged. Free some space and try again." |
| Write busy (`BUSY`) | E_WRITE_BUSY "The library was busy and the change wasn't saved. Try again." |
| Reorder/Sort in All or Find | D_REORDER_LIST "Open a group on the left to reorder it." |
| Reorder under a filter | D_REORDER_FILTER "Set Show to All to reorder." |
| Group Reorder while hidden groups are not shown (review S7) | D_REORDER_HIDDEN_GROUPS "Show hidden groups to reorder." |
| Reorder while the PIN leaves channels out (review S7) | D_REORDER_LOCKED "Enter the PIN to see every channel before reordering." |
| Reorder under Select all | D_REORDER_ALL "Too many to move by hand. Use Sort, or tick fewer channels." |
| Rename/Set number with several ticked | D_SINGLE "Pick one channel to rename or number it." |
| Show on rule-hidden only | D_RULE "Hidden by a rule. Change it in Hide rules (advanced settings)." |
| Unlock inside a locked group | D_LOCKED_BY_GROUP "Locked by its group, %1$s. Unlock the group to unlock this channel." |
| Move to group with one group | D_NO_GROUPS "Move needs a second group. Refresh the playlist if one is missing." |
| Groups Actions chip with no ticks | D_GROUP_TICK_FIRST "Tick groups with OK first." |
| Number out of range | the pad's existing `ERR_NUMBER_RANGE` (range 1–99999) |
| No keyboard (Find, Rename) | the existing settings text editor fallback ("No keyboard on this TV: use your phone") |

Rename, Hide and Lock are left out of the menu on the All channels and Results rows (§2.3), so they need no
disabled-reason keys.

Logs: the editor never logs the Find query or custom names at INFO or above. They are not secrets, but they are the
user's.

---

## 6. Tests and acceptance

### 6.1 JVM, `:core` (`core/src/test/kotlin/app/thebox/core/editor/`)

- `ChannelEditorKeyTableTest`, over `core/src/test/resources/editor_keys.csv` (every pane × layer × key, as
  `settings_keys.csv`). It includes:
  - unlisted keys return `true`, and ← in the Groups pane is consumed;
  - CONTEXT_MENU and digits in layers are ignored;
  - ← closes choice lists, pickers and menus.
- `ChannelEditorReachabilityTest`: enumeration over the settings alphabet, **including CONTEXT_MENU**
  (`SettingsReachabilityTest.kt:30`), from `open(pid, null)` and `open(pid, groupKey)`, with the PIN set and unset and
  with 0, 1 and many groups. It checks:
  - every `EditorAction` is emitted;
  - every layer is opened;
  - from every state, at most 12 BACKs return `false`.

  A second run without CONTEXT_MENU checks that every channel action is still reached (→ opens the menu).
- `ChannelEditorBackLadderTest`: each rung of §2.5, in order.
- `ChannelEditorSelectionTest`:
  - tick and `key → index` order;
  - `AllExcept` counts;
  - ticks cleared on a list or filter change ("N unselected");
  - ticks cleared after their write, while ticks made during a save survive;
  - the target built for each write.
- `ChannelEditorMoveModeTest`:
  - block gather of non-contiguous ticks in list order;
  - lifted rows drawn with their pages evicted;
  - clamps at both ends and below the fixed rows;
  - CH± by 8;
  - the drop emits one `ReorderChannels` / `ReorderGroups` with the right `toIndex`;
  - cancel restores;
  - Reorder is disabled under `AllExcept`.
- `ChannelEditorPagingTest`:
  - window eviction (≤ 6 pages);
  - stale versions and tokens dropped;
  - cursor restore by the returned `cursorIndex`, falling back to the index;
  - the debounce runs through `nextDeadlineMs` / `Tick`.
- `ChannelEditorWriteQueueTest`: FIFO order kept over Hide → Move → Lock (none dropped or merged), the bound of 8 with
  `N_QUEUE_FULL`, and `pendingWrites` / "Saving…".
- `ChannelEditorSummaryTest`:
  - the menu opens `loading` and fills on `Summary`;
  - a stale token is ignored;
  - the lines are right for single, many, mixed hidden, rule-hidden, `lockedByGroup` and `fromLockedGroup`;
  - the ruled order, with no wrap and with CH± jumps.
- `ChannelEditorParentalTest`:
  - E7, and the locked-out note row in All and Find;
  - the PIN is asked for Unlock, Reset and a move out of a locked group, never for Lock;
  - a **refused** PIN (`PinResult(false)`) writes nothing and shows the action's text;
  - `WriteResult.Failed(REFUSED)` shows the same text.
- `ChannelEditorTextsTest`: every (P) key goes through `count`; no key splices a verb.
- Settings: `SettingsShellLinkEditorTest`. It covers:
  - the `editText` validator (an empty commit with `{ null }`; HintValidator otherwise);
  - a callback on unchanged text, on cancel, and on each `closeOverlays` cause (OPEN_GUIDE, EXIT, push, replace, pop,
    onLeave);
  - the `editNumber` callback;
  - `requestPin` granted / refused / no PIN / stale token;
  - the interface defaults answering;
  - the `keyHandler` order (not consulted while a pad, confirm, menu, move or choice is open).
- Updated: `SimpleRowCountTest` (still 35, with the new CHANNELS row set), `WpdPagesTest` (the editor row;
  `channels.hidden` under advanced), `ReviewFixRowsTest.sectionOfMatchesParentChain` (the new route), `RealPages`
  fixtures.
- Guide: `GroupMenuEditTest` (EDIT is last and opens the gated route). `PinRoutingTest` (existing) is updated for the
  new item.

### 6.2 `:data` (Robolectric, `data/src/test/kotlin/app/thebox/data/editor/` unless stated)

- `ChannelEditorRepositoryTest`:
  - id order equals the guide lineup order for Group and All (Q2 order);
  - filters;
  - Find over raw and custom names;
  - group rows and counts, including rule-hidden;
  - `lockedOut` and access in All, Find and groups;
  - `indexOf`, `locate`, `playlistExists`.
- `EditorSummaryTest`: the counts over Keys and AllOf, under each access.
- `EditorBulkWritesTest`:
  - an AllOf hide of 5,000 in one transaction (the invalidation tracker fires once);
  - a Keys hide;
  - a Favorites bulk add to the default list, kept in order, with the list created when none exists;
  - Reset keeps the order of non-moved channels, the playback prefs and Favorites;
  - Reset of a moved channel nulls the group and the order;
  - `GONE` when every key has left.
- `EditorOrderWritesTest`:
  - the dense rewrite covers hidden, rule-hidden, moved-in and missing members;
  - block splice;
  - Collator name sort ("Général" vs "General", case);
  - number sort;
  - Provider order clears;
  - moving into a custom-ordered target appends `max + 1`;
  - moving into an uncustomised target materialises it, then appends;
  - groups order goes through `GroupDao.setGroupOrder` over all keys.
- `GuideGroupMoveTest` (`data/src/test/.../guide/`): the guide's Move up/down swaps with the nearest visible group
  inside `orderedKeys`, hidden groups keep their indices, and nothing collides.
- `EditorBulkLockTest` (`data/src/test/.../parental/`):
  - `setChannelsLocked` / `setGroupsLocked` each run as one transaction;
  - unlock, Reset and move-out are refused under `refusesLowering()` (`REFUSED`);
  - Lock is always allowed.
- `MoveToGroupTest`:
  - the effective group in `GuideQueries.lineup` (both halves) and in the `GroupDao.panelGroups` / `groups` /
    `GroupAdminDao.entries` counts;
  - the target's hidden and lock state apply;
  - a move back writes null;
  - **`TuneGuardImpl`**: a channel moved into a locked group gets NEEDS_PIN, a channel moved out gets ALLOWED, and
    `groupOf` returns the effective group;
  - search, Fix guide data lists and the favourites lineup see the effective group;
  - a missing target falls back to the provider group.
- `GroupPurgeClearsMovesTest` (`data/src/test/.../repo/`): **target in grace: still locked** (and still hidden; the
  `TuneGuard` says NEEDS_PIN while the target is missing and not yet purged); a returning group's
  `custom_sort_index` is cleared by `RoomMergeSink.begin`; `RoomMergeSink.updateGroups` purging a target clears its
  `custom_group_key` and `custom_sort_index` in the same batch. A provider group left with only moved-in channels is
  purged and its channels go home.
- `ProviderGroupJoinGuardTest` (`data/src/test/.../db/`): the reader allowlist of §3.6.
- `LibraryDatabaseMigrationTest` (existing; it loops to `VERSION`) gets a v4 → v5 case: the column and the index
  exist, and existing rows are NULL.
- `BackupCustomGroupTest` (`data/src/test/.../backup/`):
  - a round trip;
  - an old backup without the column (merge keeps it, replace nulls it);
  - **a restore with a dangling key**, dropped silently;
  - `MergePlanner.CHANNEL_PREF_COLUMNS` contains the column.
- `GuideRefreshAfterEditTest` (`data/src/test/.../guide/`): `GuideLibraryImpl.lineup` and `sources` re-emit once after
  each write type. A move-only pref changes `prefsSignature` (select list and WHERE). A watch-history write does not
  emit the editor's `changes()`.
- `EditorPerfTest` (the §4 item 8 numbers, logged as emulator; it fails only at 3× budget on the JVM).

### 6.3 `:ui`, `:app` and device

- `ChannelEditorScreenTest` (Robolectric Compose):
  - both panes, the fixed group rows, ticks, badges, placeholders;
  - the lifted block;
  - menu anchoring and its loading state;
  - E7, the locked-out note, empty texts;
  - the notice at y 956, not over row 8.

  `ChannelEditorGoldenTest` → `ui/src/test/resources/golden/channel-editor-1080p.png`.
- `ResourceEditorTextsTest`: every `EditorText` maps to a string or a plural; the plurals render "1 channel selected" /
  "3 channels selected".
- `MigrationOnDeviceTest` (`app/src/androidTest/.../settings/`, existing; it asserts 4) is updated to 5, with a v4 → v5
  case.
- `GroupLineupPlanTest` (`app/src/androidTest/kotlin/app/thebox/editor/`, bundled driver): the plan assertions of §3.6.
- Instrumented `ChannelEditorOnDeviceTest` (I-M6-CE-1…8, `TheBox_TV_API30`, fake panel with 2,000 synthetic channels).
  It covers:
  - open from Settings and from the group menu;
  - hide 3 and see the guide drop them;
  - select all + hide;
  - reorder and see the guide's group order;
  - move to group and see the channel in the target group in the guide;
  - rename;
  - a locked group asks for the PIN;
  - the Back ladder leaves to Channels and groups.

### 6.4 Owner acceptance on the emulator

1. Settings › Channels and groups shows **Channel editor** first; Hidden channels is under Show advanced settings.
2. Guide › Groups panel › hold OK on a group › **Edit channels…** opens the editor on that group's channels. Back from
   the editor goes to Channels and groups; the Guide key goes straight back to the guide.
3. Tick three channels with OK, press → (or Menu), then **Hide**. The notice says "3 channels hidden". Press the Guide
   key: they are gone from that group in the guide.
4. Set **Show: Hidden only**, tick them, then **Show**: they are back in the guide.
5. On a big group, run **Select all**, then **Hide**, then **Show** again: each takes under half a second.
6. **Reorder**: tick two channels, Menu › Reorder, ↑ five times, OK. The guide's group shows them in the new place, and
   so does All channels.
7. In the Groups pane, Reorder a group to the top: the Groups panel lists it first, and All channels starts with its
   channels.
8. **Move to group…** a channel to another group. It shows at the end of the target group in the guide, with
   "from {group}" in the editor. Its channel number does not change.
9. **Rename…** a channel and a group: the guide and the Groups panel show the new names. An empty name brings back the
   provider's.
10. **Set number…** 501 on a channel, and typing 501 in the guide opens it. **Use the provider's number** undoes it.
11. **Sort… › Name A–Z** asks first when you had your own order, then sorts. **Provider order** undoes it.
12. With a PIN set: the Adult group asks for the PIN before listing its channels. All channels says that locked
    channels are not shown. Unlock asks for the PIN; Lock does not. Moving a channel into Adult makes the guide ask for
    the PIN when you tune it.
13. Back from anywhere: first it leaves the menu or move mode, then clears the ticks, then goes to Groups, then leaves.
14. Everything works with only the arrows, OK and Back (no Menu key): → opens the channel actions. The green key adds
    the ticked channels to Favorites.

---

## 7. Work split

### 7.0 Before any WP

The tree has about 132 uncommitted entries (the 2026-10-18 owner rulings: guide navigation, TMDB posters, type sizes),
several of which this feature touches. **The lead commits that work first.**

Then comes **commit 0** (lead). It lands every contract, key and mapping, so the three packages share no file
afterwards, and it must leave `./gradlew :core:test :data:testDebugUnitTest :app:assembleDebug` green.

Core contracts:
- `core/.../editor/ChannelEditorContracts.kt` (§3.3 types, events, effects, machine, factory);
- `core/.../editor/ChannelEditorRepository.kt` (§3.4);
- `core/.../editor/EditorTexts.kt`: the full `EditorText` enum (§5) and the interface, with `KEYS`.

Settings:
- `SettingsContracts.kt`: the `ChannelEditor` route;
- `SettingsTree.kt`: `sectionOf`;
- `SettingsPages.kt`: the three `SettingsShellLink` defaults that answer (§3.2);
- `ReviewFixRowsTest.kt`: the new route in `sectionOfMatchesParentChain`.

Parental:
- `core/.../parental/ParentalSeams.kt`: `setChannelsLocked(playlistId, target, access, locked)` and
  `setGroupsLocked(playlistId, groupKeys, locked)` (defaults loop the single calls; the channel default resolves only
  `ChannelTarget.Keys`), and `fun refusesLowering(): Boolean = false`;
- `data/src/main/kotlin/app/thebox/data/parental/ParentalSessionImpl.kt`: `override fun refusesLowering()` on the
  existing public function (`:241`); without `override`, the new supertype member stops `:data` compiling. The bulk
  bodies stay with WP-B.

Guide:
- `GuideStateContracts.kt`: `GroupMenuItem.EDIT`;
- `GuideStateMachineImpl.kt`: `toAction()` gains `EDIT -> null`;
- `GroupsPanel.kt`: the label `R.string.guide_group_edit`;
- `strings_guide.xml`: "Edit channels…".

Texts:
- `BackupRows.kt`: `BackupText.CHANNELS_EDITOR`, `CHANNELS_EDITOR_VALUE`;
- `BackupTextsImpl.kt`: their mapping;
- `strings_backup.xml`: the two strings;
- `ui/.../editor/ResourceEditorTexts.kt`, with `strings_editor.xml` and its plurals.

Docs:
- `docs/decisions/2026-10-18-channel-editor.md`, recording rulings Q1–Q4 and the numbering rule (§3.7);
- `m5-setup-settings.md` §3.3.5 and §3.3.13 (`channels.editor` SIMPLE, `channels.hidden` ADVANCED).

### 7.1 Order: commit 0 → WP-A → WP-B → WP-C, sequential on the main tree

Every step must leave the smoke build and `:data:testDebugUnitTest` green, because nothing else isolates the packages.
1. **WP-A** needs only commit 0. It is pure JVM and quick to review.
2. **WP-B** is the riskiest package (the migration, about 20 reader rewrites, the tune guard). Opus reviews its queries
   and migration before C starts.
3. **WP-C** needs A's real machine (keys, deadline, summary handling) and A's shell seams, and B's Hilt-bound
   repository. It keeps one fake repository for Robolectric screen tests, and runs the device tests against the real
   one.

### 7.2 WP-A: core state (Opus)

Creates:
- `core/src/main/kotlin/app/thebox/core/editor/ChannelEditorMachineImpl.kt` (the factory too);
- `EditorMenus.kt` (menu lines per context and summary, pure);
- `EditorMoves.kt` (block gather and splice, pure);
- `EditorWriteQueue.kt` (FIFO, pure);
- `core/src/test/kotlin/app/thebox/core/editor/*` (§6.1);
- `core/src/test/resources/editor_keys.csv`.

Touches:
- `core/.../settings/state/SettingsMachineImpl.kt`: the `ext*` callbacks, `closeOverlays`, `onPinResult`,
  `onEditorClosed`, and the `keyHandler` order;
- `core/.../settings/tree/ChannelsRows.kt`: the EDITOR row, HIDDEN → ADVANCED, the section builder;
- `core/.../guide/state/GuideStateMachineImpl.kt`: `groupMenu` and EDIT handling;
- `core/src/test/.../guide/state/PinRoutingTest.kt`;
- `core/src/test/.../settings/tree/WpdPagesTest.kt`;
- `core/src/test/.../settings/state/SimpleRowCountTest.kt` (the row set; still 35);
- `core/src/test/.../settings/state/RealPages.kt`;
- the new `core/src/test/.../settings/state/SettingsShellLinkEditorTest.kt`.

### 7.3 WP-B: data (Sonnet under this spec; Opus reviews the queries and the migration)

All paths are under `data/src/main/kotlin/app/thebox/data/` unless stated.

Creates:
- `editor/ChannelEditorRepositoryImpl.kt` and `editor/EditorQueries.kt`;
- `db/dao/EditorDao.kt`;
- `di/EditorModule.kt`;
- `data/schemas/app.thebox.data.db.LibraryDatabase/5.json`;
- the §6.2 tests (`data/src/test/kotlin/app/thebox/data/editor/*`, plus `GuideGroupMoveTest`, `EditorBulkLockTest`,
  `GroupPurgeClearsMovesTest`, `ProviderGroupJoinGuardTest`, `BackupCustomGroupTest`, `GuideRefreshAfterEditTest`);
- `app/src/androidTest/kotlin/app/thebox/editor/GroupLineupPlanTest.kt`.

Touches, database:
- `db/entity/ChannelPrefsEntity.kt`;
- `db/LibraryDatabase.kt` (VERSION 5, the `editorDao()` accessor);
- `db/Migrations.kt` (`MIGRATION_4_5`, `ALL`);
- `db/dao/ChannelPrefsDao.kt` (`upsertCustomName`, `upsertCustomNumber`, `upsertCustomGroup`);
- `db/dao/ChannelDao.kt` (`PROVIDER_GROUP_JOIN`, `CP_JOIN`, `GROUP_TAIL`, `EFFECTIVE_GROUP_TAIL`, `EFFECTIVE_GROUP_JOINS`, `SELECT_ROW`,
  `browseGroup`, `search`, `matchedWithoutData`, `UNMATCHED_HEAD`);
- `db/dao/GroupDao.kt` (counts, `upsertGroupCustomName`);
- `db/dao/GroupAdminDao.kt`;
- `db/dao/HiddenChannelsDao.kt`;
- `db/dao/FavouriteDao.kt`.

Touches, other `:data` code:
- `guide/GuideQueries.kt` (joins, `Clause.Sql`, the group union, the All order, `PREFS_SIGNATURE_SELECT` and its hash);
- `guide/GuideLibraryImpl.kt` (`groupAction` over `orderedKeys`);
- `parental/TuneGuardImpl.kt`;
- `parental/ParentalSessionImpl.kt` (bulk locks, `refusesLowering` override);
- `repo/RoomMergeSink.kt` (`updateGroups` clearing);
- `rules/ChannelsPagesImpl.kt`;
- `rules/RuleHiddenPass.kt`;
- `backup/BackupExporter.kt`;
- `backup/BackupRestorer.kt` (drop dangling keys).

Touches outside `:data`:
- `:core` `core/src/main/kotlin/app/thebox/core/backup/MergePlanner.kt` (`CHANNEL_PREF_COLUMNS`);
- `:app` `app/src/androidTest/kotlin/app/thebox/settings/MigrationOnDeviceTest.kt`;
- `data/src/test/.../db/LibraryDatabaseMigrationTest.kt`.

### 7.4 WP-C: ui and app (Sonnet under this spec)

Creates, under `ui/src/main/kotlin/app/thebox/ui/editor/`:
- `ChannelEditorScreen.kt`;
- `EditorRows.kt` (group, fixed and channel rows, the locked note);
- `EditorMenuCard.kt` (the menu, picker, choice and confirm look);
- `ChannelEditorViewModel.kt`: it hosts the machine, implements `ChannelEditorEffects` over `ChannelEditorRepository`
  and `ParentalSession`, runs the FIFO in `@ApplicationScope`, and drives the ticker from `nextDeadlineMs`;
- `ChannelEditorPageSource.kt` (the `SettingsPageSource` for the route);
- `EditorUiModule.kt` (Hilt; the page source `@IntoSet`).

Also creates:
- `ui/src/test/kotlin/app/thebox/ui/editor/*`;
- the golden PNG;
- `app/src/androidTest/kotlin/app/thebox/editor/ChannelEditorOnDeviceTest.kt`.

(`ResourceEditorTexts.kt` and `strings_editor.xml` already exist from commit 0; C only adds the tests.)

Touches:
- `ui/.../settings/SettingsScreen.kt`: the editor slot inside `SettingsContent`, drawn after the header and before the
  editor and pad overlays;
- `ui/.../settings/SettingsRowViews.kt`: `CheckBox` and `BadgeChip` become `internal`;
- `ui/.../settings/ConfirmCard.kt`: a stateless `ConfirmCardView(title, body, buttons, focused)` overload;
- `ui/.../full/PlayerParts.kt`: a `ChannelLogo(url, name, …)` overload;
- `ui/.../root/AppRoot.kt`: passes the slot;
- `app/src/main/kotlin/app/thebox/MainActivity.kt`: the `keyHandler` chain and the slot.

---

## 8. Owner rulings (2026-10-18; recorded by the lead in `docs/decisions/2026-10-18-channel-editor.md` at commit 0)

1. **Move to group: a real move.** A moved channel leaves its provider group. This needs library.db v5 and
   `channel_prefs.custom_group_key` (§3.6).
2. **"All channels" follows the user's order:** group order first, then channel order (§3.7). Consequence, decided in
   §3.7: channel numbers do not follow the new order (provider or numbering-mode numbers; "Set number…" renumbers).
3. **The Hidden channels row goes to ADVANCED.** The Channel editor row is SIMPLE, and the simple view stays at 35
   rows (§3.1).
4. **A tick means "selected".** Actions apply to the ticked items, and hidden items carry a HIDDEN badge.

No open questions remain for the owner. The second review covers §3.2, §3.5–§3.7 and §7, as the reviewer asked.

## Design review (2026-10-18)

Reviewer: adversarial review (Opus) against `main` 70da111 plus the working tree of 2026-10-18 (132 `git status`
entries now, not 118; `SettingsMachineImpl.kt` is clean, so its line numbers below are stable; `GuideStateMachineImpl.kt`
is dirty, so its numbers may move by a few lines). The owner's rulings on §8 Q1–Q4 are taken as given: Move to group
ships with v5; All follows the user's group and channel order; `channels.hidden` moves to ADVANCED; a tick means
"selected". No code was changed.

### A. Citations: what exists and what does not

1. **BLOCKER: `ImportMutex` is also held for the whole EPG build and patch, not only by the playlist merge.**
   `data/.../repo/EpgRepository.kt:311` and `:341` wrap `store.clear()` and every build/patch in
   `importMutex.withLock { writeLock.mutex.withLock { … } }`; the class doc says so (`data/.../work/ImportMutex.kt:9-15`).
   A first XMLTV import of a big provider runs for minutes, and it starts right after the playlist import, which is
   exactly when the owner will open the editor ("a user who has just imported a 20,000-channel playlist", §1). Under §3.5
   every editor write would wait, show "Saving after the playlist update…" (wrong reason) and fail with `BUSY_TIMEOUT`
   after 60 s. The lock also is not needed for correctness: the merge is several transactions (`RoomMergeSink.begin` :68,
   `apply` :91, `finish` :141), user data is keyed by `stable_key`, and `UserDataRemapper` (`repo/UserDataRemapper.kt`)
   moves any pref written meanwhile from an old key to the new one inside `finish`. The guide's own hide and group writes
   (`GuideLibraryImpl.kt:160-178`) take no lock today. **Change:** editor writes take no `ImportMutex`; each action is one
   `writeTransaction` on Room's single writer connection, which already serialises it against merge batches. Delete
   `N_SAVING_AFTER_UPDATE`, `E_BUSY` and `WriteFailure.BUSY_TIMEOUT`, or keep `BUSY_TIMEOUT` only for `SQLITE_BUSY` past
   the driver's busy timeout. If a gate against the merge is still wanted, it has to be a per-playlist merge lock that EPG
   work never holds, and §3.5 has to say why.

2. **BLOCKER: the list of "effective group" readers in §3.6 leaves out the tune guard, so a moved channel's lock state
   becomes inconsistent.** `LockedChannelIndex.SQL` (`data/.../parental/TuneGuardImpl.kt:111-124`) joins
   `channel_group g ON g.id = c.group_id`. It is "the guard every tune funnels through" (B1, :127-131) and it gives
   "Open anyway" its group (`groupOf`, :101-105). After v5, a channel moved **into** Adult is filtered out of lineups, but
   the guard says ALLOWED. A channel moved **out of** Adult shows in the target group's lineup unlocked, but the guard
   asks for the PIN and offers "Open anyway" on the old group. Other readers that join by `group_id` and are not listed
   in §3.6: `FavouriteDao.channels` (`FavouriteDao.kt:54`, parental predicate), `ChannelDao.search` (:155-166),
   `browseGroup`/`browseAll`/`atPosition`/`bySortIndex*` (they all use `SELECT_ROW`, so they are covered only if
   `SELECT_ROW` itself changes, and `browseGroup` still filters on `c.group_id = :groupId`), `UNMATCHED_HEAD` and
   `matchedWithoutData` (:180-197, :230-248, used by Fix guide data with the parental predicate), `GroupDao.groups`
   (:33-45, used by the guide's Move up/down), `ChannelsPagesImpl.kt:136` (rule-hidden count) and `RuleHiddenPass.kt:72`
   (preview count). **Change:** (a) add one constant, `EFFECTIVE_GROUP_JOINS` in `ChannelDao`'s companion, and use it in
   every UI-facing or parental reader, `TuneGuardImpl.LockedChannelIndex.SQL` included; (b) add to §3.6 a table of every
   `group_id` reader (the 15 hits of `grep -rn group_id data/src/main`), each marked "effective" or "provider, on purpose".
   The provider ones are `RoomExistingChannels.kt:21`, `RoomMergeSink.kt:233` and `:284`, `DisplayNames.kt:128` (prefix
   stripping uses the provider's group name) and `PlaylistSettingsRepository.kt:286` (Groups review after import counts
   what the provider sent). (c) Add a JVM guard test that fails when a new query joins `ON g.id = c.group_id` outside that
   allowlist. (d) `MoveToGroupTest` gains "moved into a locked group: `TuneGuard` says NEEDS_PIN; moved out: ALLOWED".

3. **BLOCKER: the group purge is not in `MaintenanceDao`, and a moved-to group can be purged while channels point at it.**
   `MaintenanceDao` (`db/dao/MaintenanceDao.kt`) only trims key tombstones. Groups are deleted by
   `RoomMergeSink.updateGroups` (`repo/RoomMergeSink.kt:231-235`), whose condition is
   `NOT EXISTS (SELECT 1 FROM channel c WHERE c.group_id = channel_group.id)`, so it ignores moved-in channels. A provider
   group that keeps only moved-in channels gets deleted. Its `custom_group_key` rows then dangle: under §3.6's SQL those
   channels match neither half of the group `UNION ALL` and drop out of every group lineup, and they lose the group's
   lock and adult state (`g` is NULL, so `COALESCE(gp.locked, g.is_adult, 0) = 0`). A backup restored on a device whose
   playlist lacks the target key (`BackupRestorer.kt:280-295` copies fields as they are) and a hand-deleted group lead to
   the same state. **Change:** (a) in the same `updateGroups` statement batch, clear the `custom_group_key` rows that
   point at the keys being deleted; (b) make readers fail safe: the effective group is the target **only if it exists**,
   otherwise the provider group. For example
   `LEFT JOIN channel_group gt ON gt.playlist_id = c.playlist_id AND gt.kind = 0 AND gt.group_key = cp.custom_group_key`
   then `g = COALESCE(gt, g0)` by column (`COALESCE(gt.id, g0.id)` driving a final `channel_group g ON g.id = …`). The
   lineup's first half then becomes `(cp.custom_group_key IS NULL OR gt.id IS NULL)`. (c) The restorer drops a
   `custom_group_key` whose target is missing and reports nothing (the channel stays in its provider group). (d) Fix the
   file name in §3.6, §6.2 (`MoveToGroupTest`: "purge clears the key") and §7.2 (WP-B touches `RoomMergeSink.kt`, not
   `MaintenanceDao.kt`).

4. **SHOULD-FIX: the backup column list is in `:core`, not in `BackupExporter`.** The export streams
   `MergePlanner.CHANNEL_PREF_COLUMNS` (`core/.../backup/MergePlanner.kt:82`, used at `BackupExporter.kt:110`); the
   restorer filters on it (`BackupRestorer.kt:283`), and `clearUserRows` nulls it (:377-381). Adding `custom_group_key` to
   that set and to `USER_PREF_PREDICATE` (`BackupExporter.kt:165`) is the whole change, and older backups then work
   unchanged. A v4 backup has no field, so merge mode keeps the device's value and replace mode nulls it. A v5 backup on
   an older build is filtered out by the same set. **Change:** name `MergePlanner.kt` in §3.6 and in §7 (a `:core` file
   that WP-B touches). Add the restored-to-missing-group case to `BackupCustomGroupTest` (finding 3c).

5. **SHOULD-FIX: `PREFS_SIGNATURE_SELECT` needs the column in its WHERE clause as well as in its select list.**
   `GuideQueries.kt:330-333` selects only rows where some guide column is non-default. A channel whose only pref is
   `custom_group_key` would not be in the hash, and a move would not re-run the lineup. **Change:** add the column to
   both, with its hash step in `prefsSignature` (:153-170).

6. **SHOULD-FIX: `ParentalControls.setChannelLocked` / `setGroupLocked` cannot be "a bulk loop inside one transaction".**
   Both are single-item `suspend` calls (`core/.../parental/ParentalSeams.kt:40-42`). `setChannelLocked` opens its own
   `withContext(io) { db.writeTransaction { … } }` (`ParentalSessionImpl.kt:210-222`), and `setGroupLocked` is a bare DAO
   call (:229-235). Looping them for an `AllOf` of 5,000 channels means 5,000 nested writer transactions (savepoints at
   best), and `setGroupLocked` silently drops an unlock while the session is locked (:230). **Change:** add
   `suspend fun setChannelsLocked(playlistId: Long, keys: …, locked: Boolean)` and
   `setGroupsLocked(playlistId, groupKeys, locked)` to `ParentalControls`, with defaults that loop, in commit 0.
   `ParentalSessionImpl` implements each as one statement in one transaction and applies `refusesLowering()` to unlocks.
   The editor's `LockChannels`/`LockGroups` writes call them, so "one parental write path" stays true. The same
   `refusesLowering()` check applies to `ResetChannels`/`ResetGroups` (they clear locks) and to a move out of a locked
   group, as `ChannelsPagesImpl.kt:238-239` does for the Groups page.

7. **SHOULD-FIX: the shell seams in §3.2 do not survive the settings machine as it is.**
   - (a) `HintValidator` rejects an empty `TextHint.NAME` (`SettingsTexts.kt:296`, `ERR_EMPTY`), and `onEditorClosed`
     validates before anything else (`SettingsMachineImpl.kt:616-630`). "Leave empty for the provider's name"
     (`EDIT_RENAME_*`, acceptance item 9) can therefore never be committed. **Change:** `editText` takes a
     `validate: (String) -> String?` (default: the hint's rule), and the editor passes "always valid" for Rename.
   - (b) `onEditorClosed` emits nothing when the text equals the initial value (:623). The callback must still be called
     (with the text or null).
   - (c) A refused PIN is never reported. `onPinResult` runs `pinPending` only when the PIN is granted (:441-447), so the
     editor's `requestPin(onResult)` would never hear `false`, and `ChannelEditorParentalTest`'s "refused PIN writes
     nothing" cannot observe a refusal. **Change:** store the callback with `pinToken` and call it with `e.granted` on
     both outcomes.
   - (d) `closeOverlays()` (:918-923) drops `editor` and `numberPad` silently: it runs on OPEN_GUIDE/EXIT (:163-165),
     `push`, `replace`, `pop` and `onLeave`. Any pending `editText`/`editNumber` callback must be called with `null`
     there, or the editor machine waits forever.
   - (e) The number pad cannot clear a value: `commitPad` rejects empty digits (:562-568). Add a menu line
     **"Use the provider's number"** when `customNumber != null` (a `SetNumber(…, null)` write), and add a
     disabled-reason key for it.
   - (f) The interface defaults (`= Unit`) never call back, so test doubles hang. Make them call `onClosed(null)` and
     `onResult(false)`.
   - The key-order claim is right. `act` consults `keyHandler` at `SettingsMachineImpl.kt:169`, before `numberPad`,
     `confirm`, `menu`, `move` and `choice`, while the interface doc says "after overlays" (`SettingsPages.kt:121-123`).
     The proposed order fixes the code to match the doc, and the phone page is unaffected. Keep it.

8. **NIT: smaller citation errors.**
   - `DiagPages.kt` has no exhaustive `when` over routes: `handles` and `page` both end in `else` (`ui/.../diag/DiagPages.kt:103-117`).
     Drop it from commit 0 (it is also a dirty file).
   - The exhaustive ones are `SettingsTree.sectionOf` (`SettingsTree.kt:64-76`, a commit 0 change as written) and, for
     the guide, `GroupMenuItem.toAction()` (`GuideStateMachineImpl.kt:2332-2337`). Commit 0 must add
     `GroupMenuItem.EDIT -> null` there, or `:core` does not compile. `ReviewFixRowsTest.sectionOfMatchesParentChain`
     (:136-152) gains the new route.
   - `GuideUi.Chip` is the top-level `Chip` in `GuideUi.kt:79`.
   - `ChannelLogo` takes a `GuideChannel` (`PlayerParts.kt:148-153`), so the editor needs a `(url, name)` overload.
   - `SimpleRowCountTest` is in `settings/state`, not `tree`.
   - `Header` is private in `SettingsScreen.kt`, and the phone slot returns before `SettingsContent`, so before the
     overlays (:81-85). The editor slot must be drawn inside `SettingsContent` (header, then slot, then `ui.editor` and
     `ui.numberPad`).
   - Renaming a group through `GroupDao.upsertPrefs` (a full-row `@Upsert`, `GroupDao.kt:83-84`) clobbers hidden, lock
     and order on a read-modify-write race. Add a column-targeted `upsertGroupCustomName`, like `upsertGroupHidden`
     (:91-97).
   - The settings `ITEM_ROW_H` (72) and `PANE_Y` (144) are real tokens; `PANE_H` is 872, not 800 (fine, the editor sets
     its own). `MENU_W` is 420, against the design's 480.

### B. The editor machine (mocks E1–E8, Back, toolkit)

9. **SHOULD-FIX: the UI state cannot draw E1 and E8 as specified.**
   - (a) No field holds the Groups toolbar's `Hidden: Show` chip (`TB_HIDDEN_GROUPS`), yet `GroupTarget.AllOf(showHidden)`
     depends on it. Add `showHiddenGroups: Boolean` and the toggle's behaviour.
   - (b) The Groups pane rows are "All channels", the optional "Results for …", then groups, but
     `EditorWindow<EditorGroupRow>` has only real groups (`groupKey: String`). Model the pane as a list of
     `EditorList` entries: index 0 is `All`, index 1 is `Find` when present, the rest are `Group(key)`. The window covers
     the groups only, with a fixed offset, so cursor, tick, move-mode clamps and "Reorder disabled on All/Find" are
     defined. Rename/Lock/Hide on the All row need disabled reasons or must be left out.
   - (c) The title counts "64 · 12 hidden" and "120 channels · 8 hidden" need `hidden` in the `Groups` and `Channels`
     events; only `count` is there.
   - (d) "Sort: %1$s" has no state, because a sort is a one-shot write. Make the chip **"Sort…"** (an action), and keep
     `EditorGroupRow.customOrder` to say whether "Your order" exists.
   - (e) `ADD_TO_LIST` ("Add to list…") has no picker state. Either drop it from v1 (Favorites goes to the default list,
     `FavouritesRepository.defaultList()`, created inside the write transaction) or add `ChoiceKind.FAVORITE_LIST`.
     `REMOVE_FAVORITE` must say "from every list" or "from the default list".
   - (f) Add an `E_PLAYLIST_GONE` trigger: a `PlaylistGone` event, or `Groups(count = -1)`.
   - (g) Picker paging: `GroupRows` does not say whether a page is for the pane or for the picker. Share one cache or tag
     the event.

10. **SHOULD-FIX: the machine has no deadline.** Notice fade (`NOTICE_MS`) and group-follow debounce (150 ms) need time,
    but `ChannelEditorMachine` exposes neither `nextDeadlineMs` (the settings and guide machines do,
    `SettingsMachineImpl.kt:66-67`) nor a rule for who sends `Tick`. **Change:** add
    `val nextDeadlineMs: StateFlow<Long>`, driven by the existing ticker like the settings machine.

11. **SHOULD-FIX: "queue of 1, later writes coalesce" loses user actions.** Hide (in flight), Move (queued), then Lock
    leaves only one of Move or Lock. Two different `EditorWrite`s cannot be merged. **Change:** keep a FIFO of pending
    writes (bound it at 8 and refuse further actions with a notice while it is full), or block action-menu opening while
    `saving` and show "Saving…". Each write's target is snapshotted when the action runs, and new ticks made while
    saving survive the clear. The view model runs writes in an application-scoped coroutine so that Back out of the
    editor does not cancel a queued write.

12. **SHOULD-FIX: the selection scope is ambiguous.** §2.6 keeps group A's ticks "until you tick in another group", but
    it does not say what Menu acts on in group B before the first tick there: A's invisible ticks or B's focused row.
    **Change (simplest, and what the machine can prove):** ticks belong to `(list, filter)`. Changing either clears them,
    with "3 unselected" (the existing `N_UNSELECTED`). The cross-group pattern is "tick in All channels or in Find", which
    already exists. Drop `N_SELECTION_MOVED`.

13. **SHOULD-FIX: Reorder under Select all, and block gather in general.** `Selection.AllExcept` has no key list, so
    "lift the ticked rows" over 15,000 rows either builds an O(n) key list or is undefined. `Selection.Keys` is a `Set`,
    so "in their current relative order" needs each key's index, and evicted pages lose it. **Change:** Reorder is
    disabled under `AllExcept`, with `D_REORDER_ALL` "Too many to move by hand. Use Sort, or tick fewer channels."; the
    machine keeps `key → index` for explicit ticks; `EditorMove` carries the lifted rows (`List<EditorChannelRow>`) so
    the block can be drawn at any position without its source pages loaded.

14. **SHOULD-FIX: the toolbar is reachable only from row 0, and the Groups "Actions" chip has no defined target.** With
    five keys, reaching Find, Show or Sort from row 3,000 means holding ↑. In the Groups pane with nothing ticked, the
    "focused group" while the toolbar has focus is undefined (row 0 is All channels). **Change:** (a) also put
    **Find…**, **Show…** and **Sort…** as the last lines of the action menu, so they are reachable from any row; (b) the
    Groups "Actions" chip acts on the ticked groups, and is disabled with "Tick groups with OK first." otherwise;
    (c) `ChannelEditorReachabilityTest` uses the settings alphabet, which includes `CONTEXT_MENU`
    (`SettingsReachabilityTest.kt:30`; long OK is part of the five-key rule, m5 §0 item 7). Excluding it is stricter than
    the rest of Settings and pushes group actions onto the toolbar path for no gain.

15. **NIT: Back and the layers.**
    - The ladder in §2.5 is sound, and nothing traps the user: every layer's Back closes it; the lowest rung returns
      `false` and `popPage` runs (`SettingsMachineImpl.kt:373`, `:687-712`); OPEN_GUIDE/EXIT leave from any depth
      (:163-166).
    - Make ← close choice lists and pickers, as the settings choice list does (`SettingsMachineImpl.kt:478`); the
      design's "nothing" is inconsistent with it.
    - Define CONTEXT_MENU while a layer is open (ignore it).
    - Define DIGIT. Proposal: in the Channels pane, digits jump to that channel number (number-entry overlay look);
      elsewhere they are ignored.
    - `act` must return `true` for every unlisted key. A `false` LEFT would reach `pageKey` and pop the page
      (`SettingsMachineImpl.kt:363-365`). The design says so, but the key table should test it.
    - Entry from the guide's group menu lands under `[Root, Section(CHANNELS)]`, so Back goes to Channels and groups, not
      to the guide. This matches Channel options today (`GuideStateMachineImpl.kt:1449`, `:816-841`). Say it in §2.1,
      because users will expect Back to return to the guide.

16. **NIT: toolkit.** Nothing in §3.3 is Android or Compose bound. `EditorConfirm.args: List<Any>` should be typed (e.g.
    `List<String>` of formatted args), so the key-table CSV can print it.

### C. Data path

17. **SHOULD-FIX: the group `UNION ALL` in §3.6 is not valid SQL as written, and its plan check is too weak.**
    - (a) In a compound SELECT, every ORDER BY term must be a result column. `custom_sort_index IS NULL` is an
      expression, so SQLite raises "1st ORDER BY term does not match any column in the result set". Select `o1, o2, o3`
      aliases in both halves, or wrap: `SELECT id FROM (… UNION ALL …) ORDER BY o1, o2, o3`.
    - (b) The first half must say `JOIN channel_group g0 ON g0.id = c.group_id` (inner) and
      `WHERE g0.playlist_id = ?2 AND g0.kind = 0 AND g0.group_key = ?3`. Otherwise the planner may drive from
      `index_channel_browse (playlist_id=?, kind=?)`. That is a `SEARCH`, so "no `SCAN channel`" passes, yet it reads
      every channel of the playlist. **Change:** the `EXPLAIN QUERY PLAN` assertion requires
      `SEARCH g0 USING INDEX index_channel_group_identity` and `SEARCH c USING INDEX index_channel_group (group_id=?)`
      (or the browse index with `group_id`) in half 1, and `SEARCH cpm USING INDEX index_channel_prefs_group` in half 2.
      Assert on both drivers, or on the bundled one only and say so.
    - (c) `GuideQueries.lineup` also runs an `ARCHIVE_DAYS` aggregate over `q.from`/`q.where` (:51-57), and the `Clause`
      type (:261-262) cannot express a UNION. Say that `Clause` gains a `sql` form, so the archive bound is
      `SELECT … FROM (union)`.
    - (d) The `INSERT … SELECT … ON CONFLICT DO UPDATE` for AllOf hide needs a `WHERE` on the SELECT (SQLite's parsing
      ambiguity with a join's `ON`). It will have one; note it in the DAO doc.

18. **SHOULD-FIX: order semantics for move, reset and the guide's group Move up/down.**
    - (a) A moved channel keeps its old `custom_sort_index`. With
      `ORDER BY custom_sort_index IS NULL, custom_sort_index, sort_index`, a non-null index sorts **before** every
      provider-ordered channel, so into a target with no custom order the channel lands at the top, or at a colliding
      index. **Change:** `MoveChannels` sets `custom_sort_index = max(target) + 1 … + n` when the target has a custom
      order. Otherwise it sets null, and the moved channel interleaves by its playlist-wide `c.sort_index`. Pick one of
      those positions for "no custom order" and write it down (recommended: materialise the target's order so moved
      channels go to the end, which is what a user expects).
    - (b) The channel Reset clears `custom_sort_index` (§3.5). In a custom-ordered group the reset rows fall to the
      bottom (null sorts last). Order is a group property with its own undo (Sort › Provider order). **Change:** a
      channel Reset leaves `custom_sort_index` alone, and `C_RESET_BODY` stops saying "order".
    - (c) `setChannelOrder` must cover every channel whose effective group is the group, including hidden, rule-hidden
      and moved-in ones, and missing ones too (otherwise a returning channel collides).
    - (d) Three writers rewrite group order over **different key sets**. The editor writes all groups. The Groups page
      writes `GroupAdminDao.orderedKeys` (non-missing, hidden included, `GroupAdminDao.kt:41-50`;
      `ChannelsPagesImpl.kt:243-246`). The guide's Move up/down writes `groupDao.groups(…, unlocked = true)`, which
      excludes hidden and rule-hidden groups (`GuideLibraryImpl.kt:169-176`, `GroupDao.kt:33-45`), so hidden groups keep
      stale indices that collide. **Change:** WP-B makes `GuideLibraryImpl.groupAction` swap with the neighbouring
      visible group inside the full `orderedKeys` list and rewrite that.
    - (e) A name sort uses a Kotlin `Collator` on IO (SQLite `NOCASE` is ASCII-only; "FR | Général"). A number sort uses
      the effective number (custom, then the numbering mode).

19. **SHOULD-FIX: Reset, precisely.** It answers the brief's "exactly what the editor wrote" only partly, and should say
    so. Channel Reset clears name, number, group move, hidden and lock (after finding 18b, not order). It keeps
    Favorites (the editor writes those too: say "Favorites stay" in the confirm, which the body already does), plus
    logo, guide shift, playback and history. Group Reset (`GroupDao.deletePrefs`) clears hidden, lock, order and name,
    but not moves into or out of the group. Say whether "Reset group" also returns the channels moved out of it
    (recommended: no). Both clear locks, so both ask for the PIN when one is set, and the data layer refuses them under
    `refusesLowering()` (finding 6). A Reset never touches `rule_hidden`. Good as designed.

20. **SHOULD-FIX: the access rule for All and Find.** With the session locked, are locked channels listed in "All
    channels" and in Find? E7 covers only a locked group's pane. If they are listed, Select all plus Hide or Move acts on
    channels the user cannot see. **Change:** under LOCKED_* the All and Find lists use the guide's access predicate:
    locked channels are not listed, and a line "Locked channels are not shown. Press OK to enter your PIN." heads the
    list. `ChannelTarget.AllOf` is re-run with the same `access` in the write.

21. **NIT: the v5 migration is safe as specified.** `ALTER TABLE … ADD COLUMN custom_group_key TEXT` gives NULL for every
    existing row (no behaviour change), and `CREATE INDEX IF NOT EXISTS index_channel_prefs_group` matches
    `Migrations.kt`'s pattern (`:26-67`). The entity's `@Index` name and columns must match exactly for Room's validation.
    Also list: `LibraryDatabaseMigrationTest` (:47 loops to `VERSION`) and the on-device
    `app/src/androidTest/.../settings/MigrationOnDeviceTest.kt:17`, which asserts `4` and must accept `5` (or gain a
    v4 → v5 case). There is no downgrade path (`LibraryDatabase.build` has none, :115-132): an older APK installed over
    v5 crashes on open, as it does for v4 today. Say it in the release notes.

22. **NIT: one transaction is not quite one re-query.** `GuideLibraryImpl.trigger` merges a table flow and the
    `channel_prefs` signature flow (:201-213). A write that touches `group_prefs` and `channel_prefs` together (a future
    group Reset that clears moves) produces two emissions. `versionOf` dedupes the result (`GuideQueries.kt:377-381`),
    but it is two queries. Fine; correct the sentence in §3.7.

### D. Performance

23. **SHOULD-FIX: the O(n)-on-main-thread operation is the action menu's summary.** E3's lines and counts ("Hide 2" plus
    "Show 1", "2 are hidden by a rule", Lock or Unlock, Add or Remove Favorites, enabled or disabled) depend on the
    flags of **every** selected row. Under `AllExcept` (15,000 rows) or with explicit ticks whose pages were evicted
    (at most 6 pages are kept), the main-thread machine either iterates the whole selection or shows the wrong lines.
    **Change:** add `ChannelEditorEffects.summarize(target)` → `ChannelEditorEvent.Summary(total, hidden, ruleHidden,
    locked, favorite)`, one `SELECT COUNT(*), SUM(CASE …)` on IO over the same predicate as the write. The menu opens
    when it arrives (a ≤ 1-frame wait is fine; a placeholder title meanwhile). The second O(n) is **cursor restore by key
    after a Sort or Move**: the machine holds only loaded pages, so finding the key's new index in a 20,000-id list
    belongs to the view model on IO, which returns `cursorIndex` with the `Channels` event.

24. **SHOULD-FIX: the editor's own `changes(pid)` must filter `channel_prefs` the way the guide does.** Playback goes on
    behind Settings (`AppRoot.kt:84`, `HoleMode.COVERED`), and it writes `last_watched_at`, `playback_flags` and
    `fps_milli` (`ChannelPrefsDao.kt:24-50`). As specified, each of those writes re-runs a 20,000-id list query and the
    group rows. **Change:** watch `channel_prefs` through an editor signature (the guide's
    `PREFS_SIGNATURE_SELECT` plus `custom_group_key`, without the recent count), as G-S6 does.

25. **NIT: what the performance targets prove.** The §4 item 7 targets are measured on the API 30 emulator, which the plan
    marks correctness-only (`00-master-plan.md:109`). Keep the numbers, but log them as "emulator" and add the same test
    to the open device gates (Onn 4K / Fire TV Stick 4K). Q2 makes the guide's All lineup sort by joined columns: a temp
    B-tree over 20,000 rows on every lineup re-query, so add "All lineup ids P90" to `EditorPerfTest`, guide side.

### E. UX ruling on the press budget (item 5)

26. **SHOULD-FIX (ruling): no wrap; order the menu by frequency; 4 presses is fine for Reorder.**
    - The wrap is the only thing that makes Reset 3 presses, and it is wrong twice over. No other menu in the app wraps:
      the settings menu clamps (`nextEnabled`, `SettingsMachineImpl.kt:1079-1086`) and the guide's group menu clamps
      (`GuideStateMachineImpl.kt:1310-1311`). And a wrap puts the destructive item one ↑ away from the first item, where
      an overshooting thumb lands.
    - Reorder at 4 is acceptable: the press that matters is the drag that follows (k × ↑/↓ plus OK), so the fourth press
      adds about 15% to the cheapest reorder.
    - The ≤ 3 target should hold for what the owner asked for, "click multiple and hide", and for moving.
    - **Channel menu, in order:** Hide or Show · Move to group… · Reorder · Add to (Remove from) Favorites · Lock or
      Unlock · Rename… · Set number… · Use the provider's number (when one is set) · Select all in this list · Find… ·
      Show… · Sort… · Reset to provider settings…. With a mixed selection, the second of Hide/Show goes **after** Reorder,
      so Move stays on line 1.
    - Drop "Select none" from the menu: Back clears the selection in 1 press (§2.5), and the footer says so.
    - **Group menu:** Hide or Show · Reorder · Lock or Unlock · Rename… · Edit channels (→) · Sort groups… · Select all ·
      Reset….
    - **Presses:** tick 1; Hide/Show 2 (→ or Menu, OK); Move to group 3 (Menu, ↓, OK); group Reorder 3; channel Reorder
      4; Favorites 5; Reset, the last line, about 13 with ↓ alone, which is deliberately far. Optionally, CH± in this
      menu jumps to the first or last line, which makes Reset 3 (Menu, CH−, OK) without a wrap.
    - **Accelerators (additive, never the only path):** FAVOURITES (the green key, `DefaultKeyMap.kt:49`) toggles
      Favorites on the selection, in 1 press. Nothing else needs a dedicated key.
    - **Conflicts:** none with `DefaultKeyMap`. In the GUIDE context CH± are ZAP_UP/ZAP_DOWN and PAGE± are
      PAGE_UP/PAGE_DOWN (:35-36), both already "page" in Settings (:359-360). Menu and long OK are CONTEXT_MENU (:28-29).
      Long Back is PLAY in the guide context (:32), and the editor ignores it as Settings does. ← normally pops a
      settings page (`SettingsMachineImpl.kt:363-365`), and the editor deliberately consumes it, so keep that in the key
      table.
    - Update the press-budget table in §2.4 to these numbers.

### F. Texts

27. **SHOULD-FIX: gaps in "reason + next step".**
    - `WriteFailure.GONE` has no text. Add `E_GONE` "These channels were removed by the last playlist update. The list
      has been refreshed."
    - `E_PIN_NEEDED` has no next step, and it splices a verb (`%1$s`), which does not translate. Use one key per action:
      `E_PIN_UNLOCK`, `E_PIN_RESET` and `E_PIN_MOVE`, each "… Nothing was changed. Choose it again and enter the PIN."
    - Add `D_LOCKED_BY_GROUP` "Locked by its group, {group}. Unlock the group to unlock this channel." Without it, a
      channel Unlock inside a locked group does nothing visible: `COALESCE(gp.locked, …)` still applies, see
      `ChannelDao.kt:276-283`.
    - `D_NO_GROUPS` needs a next step ("Move needs a second group. Refresh the playlist if one is missing.").
    - Add disabled reasons for Rename/Hide/Lock on the All channels and Find rows.
    - `E_NUMBER_RANGE` duplicates the pad's own `ERR_NUMBER_RANGE` (`SettingsMachineImpl.kt:567`). Use the pad's.
    - Counted strings (`MENU_TITLE_*`, `CHANNELS_COUNT`, `PICKER_TITLE`, `C_RESET_TITLE`, `N_*` counts) must be Android
      `plurals` ("1 channels selected"). With nothing ticked the menu title is the channel's name.
    - The user-facing texts are American throughout ("Favorites", "program" does not occur, "color"). The prose of §2.4
      ("Behaviour", "colour") is the document's own and fine.

28. **NIT: layout.** The notice line at `channels pane bottom − 56` (y 888–928) overlaps the 8th row (y 832–904). Put the
    notice at y 960, under the panes and above the footer, or show 7 rows. E1's "2 groups selected" belongs in the
    Groups pane title.

### G. Engineering rules

29. **NIT: rules are kept.**
    - Settings covers the hole (`HoleMode.COVERED`, `AppRoot.kt:84`), with an opaque canvas (`SettingsContent`
      `.background(SettingsUi.Canvas)`). The editor is a sibling of the surface, not an ancestor, and "glass without blur"
      over an opaque canvas is fine. The move-mode lift must not use `Modifier.shadow` (which is a `graphicsLayer`);
      `MoveModeRow` does not.
    - No parsing is involved.
    - User data stays keyed by `(playlist_id, stable_key)` and `(playlist_id, kind, group_key)`.
    - Two side-by-side `LazyColumn`s are not nested. The guide-grid rule does not apply.
    - Logs: the editor must not log the Find query or custom names at INFO. They are not secrets, but they are the
      user's.

### H. Work split and order (item 8)

30. **SHOULD-FIX: the packages are not disjoint after commit 0 as written.**
    - **`BackupText` (WP-A, `BackupRows.kt:76`) and `BackupTextsImpl.res` (WP-C, `BackupTextsImpl.kt:17`, an exhaustive
      `when`).** The new `CHANNELS_EDITOR*` keys break `:ui`'s build until C lands. Move the enum entries, the mapping
      and `strings_backup.xml` into commit 0. Do the same for `EditorText` and the `ResourceEditorTexts` skeleton. Every
      key in §5, plus those added here, goes in at commit 0.
    - **`GroupMenuItem.EDIT`** needs the `toAction()` branch in `GuideStateMachineImpl.kt` (WP-A's file) at commit 0.
    - **WP-B touches `:core`:** `MergePlanner.kt` (finding 4) and the `ParentalControls` bulk signatures (finding 6,
      commit 0).
    - **WP-B touches `:app`:** `MigrationOnDeviceTest.kt` (finding 21), while WP-C "owns" `app/`.
    - **WP-B must also take:** `TuneGuardImpl.kt`, `FavouriteDao.kt`, `RoomMergeSink.kt`, `GuideLibraryImpl.kt`
      (findings 2, 3 and 18d) and `ChannelsPagesImpl.kt`. None of these are in §7.2.
    - **WP-A also takes:** `ReviewFixRowsTest.kt`, `SimpleRowCountTest.kt` (it stays at 35, but the CHANNELS row set
      changes) and `RealPages.kt` (it builds the section).

31. **Order (sequential on the main tree, no worktrees).** The lead commits the 132-entry tree first, then commit 0 (all
    contracts, enums, text keys and mappings, the `ParentalControls` defaults, `sectionOf`, the `toAction` branch, the
    `GroupsPanel` label). Every step below must leave `./gradlew :core:test :data:testDebugUnitTest :app:assembleDebug`
    green, because nothing else isolates the packages.
    1. **WP-A (core).** It needs only commit 0, and it is pure JVM, so it is fast to review. It delivers
       `ChannelEditorMachineImpl` and its factory, the menus, the moves, the fixed `SettingsMachineImpl` seams
       (finding 7) and the row changes.
    2. **WP-B (data).** It needs commit 0 and nothing from A. It is the riskiest package (migration, about 15 query
       rewrites, the tune guard), and per CLAUDE.md its queries and migration are reviewed by Opus before C starts. It
       delivers `ChannelEditorRepositoryImpl` plus `EditorModule`, v5, the effective-group readers and the bulk
       parental writes.
    3. **WP-C (ui and app).** It needs both: the real machine from A (keys, deadline, `summarize`), the
       `SettingsShellLink` behaviour from A, and the Hilt-bound repository from B. Being last, it can test on device
       against the real repository; it keeps one fake repository for Robolectric screen tests.

    A and B could swap places. C cannot move earlier, because its on-device tests (I-M6-CE-*) need both.

32. **NIT: record the rulings.** The document still says "proposal", and §8 still lists Q1–Q4 as open. §3.1 says
    "(Q3 asks the owner)", and §3.6 says "If the reviewer or the owner (Q1) defers". Mark them ruled
    (owner, 2026-10-18), and record the decision in `docs/00-master-plan.md` or a `docs/decisions/` note, per CLAUDE.md.
    Update `m5-setup-settings.md` §3.3.13 for `channels.hidden` → ADVANCED and `channels.editor` → SIMPLE.

33. **SHOULD-FIX: Q2's consequence for numbers.** The ruling (All follows the user's group and channel order) changes
    zap order, which is intended. With numbering mode `PLAYLIST_ORDER` or the start-offset modes, the number is derived
    from `c.sort_index` (`GuideQueries.kt:126`, `GuideLibraryImpl.kt:135-146`, `bySortIndexAccess`), so numbers in the
    All list are no longer ascending after a reorder. Number entry still finds the channel. §3.7 must say which is
    intended: the numbers follow the provider (they jump in the list), or these modes number by All position (that
    needs the lineup position on the read side, and number entry has to look it up). Recommended for v1: keep provider
    numbers, and tell users "Set number…" is how to renumber. Write it down, because TiviMate users will ask.

Verdict: revise

Fixes the proposer must make to this document before implementation:

1. §3.5: drop `ImportMutex` for editor writes and drop or limit `BUSY_TIMEOUT`, `E_BUSY` and `N_SAVING_AFTER_UPDATE`
   (finding 1).
2. §3.6: the full table of `group_id` readers (effective or provider) with `TuneGuardImpl.LockedChannelIndex.SQL`,
   `FavouriteDao.channels`, `ChannelDao.search`, `UNMATCHED_HEAD`, `matchedWithoutData`, `GroupDao.groups` and the counts;
   one `EFFECTIVE_GROUP_JOINS` constant; a guard test; a TuneGuard case in `MoveToGroupTest` (finding 2).
3. §3.6: the purge in `RoomMergeSink.updateGroups` clears dangling keys; readers fall back to the provider group when the
   target is missing; the restorer drops dangling keys (finding 3).
4. §3.6: name `MergePlanner.CHANNEL_PREF_COLUMNS`; add `custom_group_key` to the `PREFS_SIGNATURE_SELECT` WHERE (findings 4
   and 5).
5. §3.5: bulk `ParentalControls.setChannelsLocked`/`setGroupsLocked`; `refusesLowering()` on unlock, reset and move-out
   (finding 6).
6. §3.2: `editText` validator parameter; callbacks on unchanged text, PIN refusal and `closeOverlays`; a "Use the
   provider's number" action; defaults that answer (finding 7).
7. §3.3: `showHiddenGroups`; the Groups pane as `EditorList` rows; hidden counts in events; "Sort…" as an action; drop or
   specify `ADD_TO_LIST`; a playlist-gone event; `nextDeadlineMs`; a FIFO write queue; selection cleared on list or
   filter change; Reorder disabled under `AllExcept`; lifted rows carried in `EditorMove`; `summarize` and IO-side
   cursor restore (findings 9–13 and 23).
8. §3.6 and §4: valid compound `ORDER BY`; inner `g0` join with playlist and kind; plan assertions on the actual indexes;
   `Clause` for UNION (finding 17).
9. §3.5: order rules for move, reset, `setChannelOrder` coverage and the guide's Move up/down over the full key list;
   Collator sort (finding 18). Reset scope and wording (finding 19). The access rule for All and Find (finding 20).
10. §2.4: the menu order and press table from finding 26, with no wrap and without "Select none"; Find, Show and Sort in
    the menu; the Groups "Actions" target; `CONTEXT_MENU` in the reachability alphabet (finding 14); the ←, DIGIT and
    CONTEXT_MENU behaviour in layers (finding 15).
11. §5: `E_GONE`, the per-action PIN texts, `D_LOCKED_BY_GROUP`, the other disabled reasons, plurals, the notice position
    (findings 27 and 28).
12. §3.7: the numbering consequence of Q2 (finding 33); the editor `changes()` filtered by signature (finding 24).
13. §7: the corrected file lists per package, everything listed in finding 30 moved to commit 0, and the order
    A → B → C with what each step hands over (findings 30 and 31). Record the Q1–Q4 rulings (finding 32).
14. §6: the tests named above (`MigrationOnDeviceTest` update, TuneGuard, dangling key, PIN refusal, FIFO queue,
    summarize, plan assertions on the named indexes).

A second review only needs §3.2, §3.5–§3.7 and §7 after these changes. The UX in §2 is otherwise sound, and the tick
means "selected", as ruled.

## Revision log (2026-10-18)

The proposer's changes, per item of the reviewer's "Fixes the proposer must make" list (finding numbers in brackets).
No code was changed.

1. **ImportMutex [1]** (§3.5). Editor writes take no lock. Each action is one `writeTransaction` on the single writer
   connection. `N_SAVING_AFTER_UPDATE`, `E_BUSY` and the 60 s timeout are gone. `WriteFailure.BUSY` now means only
   `SQLITE_BUSY` after one retry, with the text `E_WRITE_BUSY`.
2. **group_id readers [2]** (§3.6).
   - The full table: 20 `grep` hits, 15 marked effective and 5 provider-on-purpose. It includes `TuneGuardImpl`,
     `FavouriteDao.channels`, `search`, `UNMATCHED_HEAD`, `matchedWithoutData`, `GroupDao.groups`,
     `ChannelsPagesImpl:136` and `RuleHiddenPass:72`.
   - One shared constant, `EFFECTIVE_GROUP_JOINS` (= `PROVIDER_GROUP_JOIN` + `EFFECTIVE_GROUP_TAIL`).
   - The guard test `ProviderGroupJoinGuardTest`.
   - TuneGuard cases in `MoveToGroupTest`.
3. **Purge [3]** (§3.6).
   - The purge is in `RoomMergeSink.updateGroups`, which clears dangling keys in the same batch before the delete.
   - Readers fall back to the provider group whenever the target row is absent or missing (the `gt` join).
   - `BackupRestorer` drops dangling keys.
   - `MaintenanceDao` is removed from the design.
4. **Backup and signature [4, 5]** (§3.6). `MergePlanner.CHANNEL_PREF_COLUMNS` (`:core`) is named as the column list,
   with `USER_PREF_PREDICATE`. `PREFS_SIGNATURE_SELECT` gets the column in both its select list and its WHERE, plus the
   hash step.
5. **Bulk locks [6]** (§3.5, §7.0).
   - `ParentalControls.setChannelsLocked` / `setGroupsLocked` (looping defaults at commit 0) and `refusesLowering()`.
   - The data layer refuses unlock, Reset and move-out under it (`WriteFailure.REFUSED`).
   - The UI asks for the PIN first.
6. **Shell hooks [7]** (§3.2).
   - `editText` takes `validate` (Rename passes `{ null }`).
   - Every callback is called exactly once: on unchanged text, on cancel, on each `closeOverlays` cause, and on a
     refused or stale PIN.
   - The defaults answer.
   - "Use the provider's number" (`CLEAR_NUMBER`) replaces clearing from the pad.
   - The key order fix is kept.
7. **Machine state [9–13, 23]** (§3.3).
   - Groups pane state: `showHiddenGroups`, and `GroupsPane` (fixed All/Find head + paged groups).
   - Events: `hidden` counts in `Groups` / `Channels`, a `PlaylistGone` event, picker sharing the group cache.
   - "Sort…" is an action, and `ADD_TO_LIST` is dropped (default list; Remove means from the default list).
   - Deadlines and writes: `nextDeadlineMs` with Tick, and a FIFO write queue (bound 8, never merged, app scope).
   - Selection: ticks cleared on a list or filter change (`N_SELECTION_MOVED` dropped), `Selection.Keys` carries
     `key → index`, and `EditorMove` carries the lifted rows.
   - Reorder is disabled under Select all (`D_REORDER_ALL`).
   - IO-side work: `summarize` → `Summary` before the menu fills, and cursor restore by key (`cursorIndex`) and `locate`
     both run on IO.
   - `EditorConfirm.args` is typed as `List<String>`.
8. **SQL [17]** (§3.6, §4).
   - The compound select orders by result columns `o1, o2, o3`.
   - Half 1 drives with an inner join from `channel_group g0` on playlist, kind and key.
   - Plan assertions name `index_channel_group_identity`, `index_channel_group` (or browse) and
     `index_channel_prefs_group`, on device with the bundled driver.
   - `Clause.Sql` covers the union, and the archive bound runs over `c.id IN (union)`.
   - The upsert's `WHERE` is noted.
9. **Order, Reset, access [18–20]** (§3.5, §2.6).
   - Moves go to the end: `max + 1`, or the target is materialised first.
   - A move back nulls the group and the order.
   - Reset of a non-moved channel keeps `custom_sort_index`, and `C_RESET_BODY` no longer says "order".
   - `setChannelOrder` covers hidden, rule-hidden, moved-in and missing channels.
   - The guide's Move up/down swaps with the nearest visible group inside the full `orderedKeys`.
   - Sorts use a Collator, and the number sort uses the shown number.
   - The Reset scope is spelled out (group Reset leaves moves alone).
   - Locked channels are left out of All, Find and group lists while the session is locked, with a note row, and AllOf
     writes re-run under the same access.
   - A column-targeted `upsertGroupCustomName` was added [8].
10. **Keys [14, 15, 26]** (§2.4).
    - The ruled menu orders, with no wrap and no "Select none".
    - Find…, Show… and Sort… appear in the menu.
    - The Groups "Actions" chip acts on ticked groups (`D_GROUP_TICK_FIRST`).
    - The press table is as ruled.
    - ← closes layers, CONTEXT_MENU is ignored in layers, and digits jump to a number in the channels pane.
    - Every unlisted key is consumed.
    - The green key is the Favorites accelerator.
    - CONTEXT_MENU is in the reachability alphabet.
    - §2.1 states that Back from a guide entry goes to Channels and groups.
11. **Texts [27, 28]** (§5).
    - New texts: `E_GONE`; `E_PIN_UNLOCK` / `E_PIN_RESET` / `E_PIN_MOVE` (no spliced verb); `D_LOCKED_BY_GROUP`; a next
      step for `D_NO_GROUPS`; one empty key per filter.
    - The pad's `ERR_NUMBER_RANGE` is reused.
    - (P) plural keys go through `EditorTexts.count`.
    - The notice moved to y 956, under the panes. "2 groups selected" moved into the Groups pane title.
    - No INFO logs of queries or names.
12. **Numbering and change feed [24, 33]** (§3.7). Numbers do not follow the All order (provider or numbering-mode
    numbers; "Set number…" renumbers; position numbering is a v1.1 candidate). The editor's `changes()` watches
    `channel_prefs` through `prefsSignature(false)`. The two-query note was corrected [22].
13. **Work split [30–32]** (§7, §8).
    - Commit 0 now carries every enum, text key, mapping and string, the `ParentalControls` defaults, the `toAction`
      branch, the `GroupsPanel` label, and the decision note plus the m5 doc update.
    - `DiagPages` was dropped.
    - The order is commit 0 → A → B → C on the main tree, each step green.
    - WP-B gains `MergePlanner.kt`, `MigrationOnDeviceTest.kt`, `TuneGuardImpl`, `FavouriteDao`, `RoomMergeSink`,
      `GuideLibraryImpl`, `ChannelsPagesImpl`, `RuleHiddenPass`, `ParentalSessionImpl` and
      `LibraryDatabaseMigrationTest`.
    - WP-A gains `ReviewFixRowsTest` (commit 0), `SimpleRowCountTest` and `RealPages`.
    - WP-C gains `PlayerParts.kt` (`ChannelLogo` overload).
    - Q1–Q4 are recorded as ruled.
14. **Tests [§6]**. Added:
    - `ProviderGroupJoinGuardTest`, `GroupPurgeClearsMovesTest`, the dangling-key restore in `BackupCustomGroupTest`;
    - `EditorBulkLockTest`, `SettingsShellLinkEditorTest`, `ChannelEditorWriteQueueTest`;
    - `ChannelEditorSummaryTest` / `EditorSummaryTest`, `ChannelEditorTextsTest` / `ResourceEditorTextsTest` (plurals);
    - `GuideGroupMoveTest`, `GroupLineupPlanTest`;
    - the `MigrationOnDeviceTest` update and the v4 → v5 case in `LibraryDatabaseMigrationTest`;
    - "Guide All lineup ids P90" in `EditorPerfTest`.

**Where the proposer went beyond or differed from the review, and why:**
- **Missing targets fall back** (overruled by review 2, ruling a; see below). The fallback applied when the target
  row is absent **and** when it is in its grace period (`missing_since` set), not only when it is absent. While a target group is missing it is invisible, and moved
  channels would otherwise vanish for up to the grace period.
- **Reset of a moved channel.** It does reset `custom_sort_index` (to NULL), against finding 18b's "Reset leaves
  `custom_sort_index` alone". That index belongs to the target group, so it is meaningless in the provider group.
  Reset of a channel that was not moved keeps the order, as ruled.
- **CH± in the menu.** The optional CH± first/last jump in the menu was adopted, which makes Reset 3 presses. The
  confirm card is the guard against a slip.

**After review 2 (2026-10-18):**
- **Ruling (a)** (§3.6): `AND gt.missing_since IS NULL` is dropped. The fallback to the provider group now covers only
  a target row that no longer exists, and a channel moved into a locked or hidden group keeps that state during the
  group's grace period. `GroupPurgeClearsMovesTest` asserts "target in grace: still locked" (§6.2).
- **Item 1** (§3.6): the tail is split into `CP_JOIN` and `GROUP_TAIL`, with
  `EFFECTIVE_GROUP_TAIL = CP_JOIN + GROUP_TAIL`. `RECENT_FROM` uses `PROVIDER_GROUP_JOIN + GROUP_TAIL`. The guard test
  names both pieces and rejects a second `cp` join.
- **Item 2** (§7.0): commit 0 lists `ParentalSessionImpl.kt` with `override fun refusesLowering()`.
- **NIT 3** (§3.5): AllOf lock keys are resolved inside `ParentalSessionImpl`'s write transaction.
  `setChannelsLocked` therefore takes a `ChannelTarget` and the access.
- **NIT 4** (§3.5 e): `RoomMergeSink.begin` clears a returning group's `custom_sort_index`.
- **NIT 5** (§3.5 a): the first move into a group materialises, and so freezes, its provider order;
  Sort › Provider order undoes it.
- **NIT 6** (§4): "Groups panel counts P90" is added to `EditorPerfTest`'s guide-side numbers.
- **NIT 7** (§3.6): `browseGroup` and `browseAll` are marked M1-only in the reader table.

---

## Design review 2 (2026-10-18)

Scope: a second pass over the revised §3.2, §3.5–§3.7 and §7, plus a quick look at §2.4 and §3.3, checked against the
code again. No code was changed.

**Verified, now correct:**
- **Locks and the purge:**
  - No `ImportMutex` on editor writes.
  - The reader table matches all 20 `group_id` hits, `TuneGuardImpl.kt:119` included. `LockedChannelIndex.TABLES`
    already has `channel_prefs` (`TuneGuardImpl.kt:108`), so a move rebuilds the guard.
  - The purge site is `RoomMergeSink.updateGroups` (:231-235).
- **Backup and signature:** `MergePlanner.CHANNEL_PREF_COLUMNS` (`MergePlanner.kt:82`) is the backup column list, and
  `PREFS_SIGNATURE_SELECT` gets the column in both its select list and its WHERE.
- **Group lineup SQL:**
  - The compound ORDER BY is now on result columns.
  - Half 1 drives from `g0` on the identity index.
  - Half 2 uses the alias `cpm`, so it does not clash with the tail's `cp`.
- **Group order:** the Move up/down swap goes over `GroupAdminDao.orderedKeys` (`GroupAdminDao.kt:41-50`).
- **Shell hooks:** each callback is called exactly once, including on `closeOverlays` (`SettingsMachineImpl.kt:918`),
  on a refused PIN (`onPinResult` :441), and on `cancelOpenPrompt`'s `granted = false`
  (`GuideStateMachineImpl.kt:~935`).
- **§3.3:** the state and events are now complete for E1–E8.
- **§2.4:** the menu clamps with no wrap; nothing conflicts with `DefaultKeyMap`.

### Rulings on the proposer's three deliberate choices

- **(a) Provider-group fallback during the target's grace period: rejected for locks, as written.** With
  `gt.missing_since IS NULL` in the `gt` join, a channel moved **into** a locked or adult group falls back to its
  provider group when the provider drops that group for a refresh or two. It becomes unlocked, and `TuneGuard` says
  ALLOWED, without any PIN. That breaks C-B2, the rule that nothing lowers protection without the PIN (enforced at
  `ParentalSessionImpl.kt:224-241`). The same fallback un-hides a channel moved into a hidden group. **Fix:** drop
  `AND gt.missing_since IS NULL` from `EFFECTIVE_GROUP_TAIL`. The fallback then covers only a target row that no
  longer exists, and the purge clearing (§3.6 a) and the restorer (§3.6 c) already remove such keys. During the grace
  period the moved channels behave like the group's own channels (the panel hides missing groups, `GroupDao.kt:61`),
  and they keep its lock and hidden state. `GroupPurgeClearsMovesTest` should also assert "target in grace: still
  locked".
- **(b) Reset of a moved channel clears `custom_sort_index`: accepted.** Reset of a moved channel is a move back. The
  index belonged to the target group's order, so keeping it would place the channel at an arbitrary slot in its
  provider group. Rule (b) in §3.5 says exactly this. Reset of an unmoved channel keeps its order, as finding 18b
  asked.
- **(c) CH± jumps to the first or last menu line, so Reset is 3 presses: accepted.**
  - Reset still requires the confirm card, whose focus starts on Cancel (`EditorConfirm.focused = 1`; settings'
    `ConfirmState` does the same, `SettingsMachine.kt:20`). A destructive change therefore always needs a deliberate ←
    (to the confirm button, which is on the left) then OK. (Corrected per code review N14; the code matches `ConfirmCard`.)
  - There is no wrap, so an overshooting ↑ cannot land on Reset.
  - The path without CH± (↓ × n) still exists.

### Remaining items

1. **SHOULD-FIX: `RECENT_FROM` would join `channel_prefs cp` twice.** It starts `FROM channel_prefs cp`
   (`GuideQueries.kt:352-357`), and `EFFECTIVE_GROUP_TAIL` begins with `LEFT JOIN channel_prefs cp …`, so the SQL fails
   with an ambiguous or duplicate alias. Room checks the DAO queries at compile time, but `GuideQueries` is raw SQL, so
   this would only surface in tests. **Fix:** split the tail into `CP_JOIN` and `GROUP_TAIL` (the `gt`, `g` and `gp`
   joins), with `EFFECTIVE_GROUP_TAIL = CP_JOIN + GROUP_TAIL`. `RECENT_FROM` uses
   `JOIN channel c … PROVIDER_GROUP_JOIN + GROUP_TAIL`. The guard test and §3.6 name both pieces.
2. **SHOULD-FIX: commit 0 does not compile as listed.** Adding `fun refusesLowering(): Boolean = false` to
   `ParentalControls` makes the existing public `fun refusesLowering()` in `ParentalSessionImpl.kt:241` an error
   (it hides a supertype member and needs `override`). Commit 0 must add `override` there, so list
   `ParentalSessionImpl.kt` under §7.0's parental item. `ChannelsPagesImpl.lockedSession()` (:209) can stay.
3. **NIT:** `setChannelsLocked` for an AllOf resolves the keys on IO **before** its own transaction (§3.5). The keys can
   go stale during a merge. That is harmless: the upsert only creates prefs rows for keys that later trim. Better,
   resolve inside `ParentalSessionImpl`'s transaction by passing the `ChannelTarget`, or say the gap is accepted.
4. **NIT:** `GroupAdminDao.orderedKeys` excludes missing groups (`GroupAdminDao.kt:47`). A group that comes back keeps a
   stale `custom_sort_index`, which can tie with another group's. Either include missing groups in the editor, Groups
   page and guide rewrites, or clear a returning group's index in `RoomMergeSink.begin` (its upsert sets
   `missing_since = NULL`, :71-79). Say which.
5. **NIT:** materialising a target's provider order on the first move into it (§3.5 a) freezes that group's provider
   order. A later provider re-sort is ignored, and new channels go to the end. Say this in §3.5, and note that
   Sort › Provider order undoes it.
6. **NIT:** the effective counts for the Groups panel (rows 11–13) become one grouped pass over the playlist on every
   `sources` re-emit. Add "Groups panel counts P90, 20,000 channels / 600 groups" to `EditorPerfTest`'s guide-side
   numbers.
7. **NIT:** `ChannelDao.browseGroup` and `browseAll` have no production caller outside `ChannelRepository`
   (`ChannelRepository.kt:41`, `:44`), and the effective `g.id = :groupId` filter cannot use the `group_id` index.
   Fine as specified; mark them M1-only in the reader table.

The work split (§7) is now disjoint after commit 0 except for item 2. The order (commit 0 → A → B → C) and the hand-offs
are right.

Verdict: approve with the fixes above. The proposer must make these changes before WP-A starts: ruling (a)'s change to
`EFFECTIVE_GROUP_TAIL` plus its test, item 1's tail split, and item 2's `override` in commit 0. The NITs can be settled
during WP-B.
