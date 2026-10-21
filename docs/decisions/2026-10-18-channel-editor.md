# 2026-10-18 — Channel editor (owner request; lead rulings on the design's open questions)

Owner request (verbatim): "i also would like an easier or dedicated way to edit and hide groups and channels, keep the
current method but also create a better way to click multiple and hide and also be able to organize and display the
channels like a full iptv editor". Design: `docs/design/m6-channel-editor.md` (proposal → review → revision → second
review, all 2026-10-18). The existing paths stay: the guide's group menu, the channel context menu, the Channel options
page and Settings › Channels and groups › Groups / Hidden channels / Hide rules / Favorites lists.

## Rulings on the design's four open questions

Made by the lead under the owner's brief ("like a full IPTV editor"); the owner can reverse any of them.

1. **Move to group is a real move.** A moved channel leaves its provider group. This needs library.db v5 and
   `channel_prefs.custom_group_key` (§3.6): every reader that joins a channel to its group uses the effective group
   (one shared join constant, a guard test over the allowlist of provider-group readers), the refresh purge clears
   dangling keys, and a backup restored onto a device without the target group falls back to the provider group.
2. **"All channels" follows the user's order:** group order first, then channel order (§3.7). Channel numbers do
   *not* follow the new order (provider or numbering-mode numbers stay; "Set number…" renumbers).
3. **The Hidden channels row goes to ADVANCED** so the Channel editor row can be SIMPLE and the simple view stays at
   35 rows (m5 §3.3.13). The editor's "Show: Hidden only" with bulk Show replaces it in the simple view.
4. **A tick means "selected"** (then Hide, Move to group, …); hidden items carry a HIDDEN badge. TiviMate's
   "tick = visible" was considered and rejected: one meaning for a tick across the app.

## Reviewer rulings adopted
- No `ImportMutex` on editor writes (it is held for whole EPG builds); one write transaction per action.
- No wrap-around in the action menu; menu order by frequency (Hide/Show, Move to group…, Reorder, Favorites, Lock,
  Rename…, Set number…, Use the provider's number, Select all, Find…, Show…, Sort…, Reset…); CH± jumps to first/last.
  Press budget: Hide/Show 2, Move to group 3, group Reorder 3, channel Reorder 4 (accepted), Reset 3 with the confirm card.
- Bulk `ParentalControls.setChannelsLocked` / `setGroupsLocked`; `refusesLowering()` gates Unlock, Reset and moving
  out of a locked group.

## Commit 0 (lead, 2026-10-18)
Contracts only, no behaviour: `core/editor/ChannelEditorContracts.kt`, `ChannelEditorRepository.kt`, `EditorTexts.kt`;
`SettingsRoute.ChannelEditor`; `SettingsShellLink.editText / editNumber / requestPin` defaults; `ParentalControls` bulk
locks and `refusesLowering()`; `GroupMenuItem.EDIT` (+ label); `BackupText.CHANNELS_EDITOR(_VALUE)`;
`ui/editor/ResourceEditorTexts.kt` + `strings_editor.xml`. Then WP-A (core machine, settings hooks, rows) → WP-B (data:
v5, effective-group readers, bulk writes) → WP-C (ui, app), sequential on the main tree.

## Implementation record (2026-10-18 → 19)

- Process: proposal → adversarial review (revise: 3 blockers) → revision → review 2 (approve with fixes) → commit 0
  (contracts) → WP-A core machine (Opus) → WP-B data (Opus) and WP-C ui/app (Opus) with WP-C compiling only `:ui` until
  WP-B landed → the I-M6-CE-1…8 instrumented suite on the API 30 emulator (8/8) → two code reviews
  (`docs/review/2026-10-18-channel-editor-review-core-ui.md`, `…-review-data.md`, no blockers) → three fix passes.
- Lead changes between packages: `EditorWrite.ReorderGroups.showHidden` (a drop with hidden groups not shown landed off
  by their count); `EditorText.N_GROUPS_RESET`; `ChannelEditorEvent.Changed` and `SummaryFailed` (review S2/S4);
  `EditorPerfTest`'s JVM tolerance raised to 8× (the JVM shares the machine with four other suites and review agents;
  the device numbers are the gate).
- Fix passes (2026-10-19), all with tests: core — `Changed` / `SummaryFailed` handled, a load failure confined to its
  pane (`ChannelEditorUiState.groupsFailed`), a gathered block dropped in place still written, one reload per null row
  per list, no empty text when only the PIN hides channels, own reason/confirm texts for group reorder/sort
  (`D_REORDER_HIDDEN_GROUPS`, `D_REORDER_LOCKED`, `C_SORT_GROUPS_*`, `C_PROVIDER_GROUPS_BODY`, `E_SUMMARY`,
  `GROUP_VALUE_HIDDEN`, `*_COUNT_NONE`); ui — the write runner never stops after a cancelled write, the change feed goes
  through the machine, a failed count belongs to the menu, deadlines on the monotonic clock, the row area clips at
  eight whole rows; data — a restore onto a fresh device keeps moves (no LIVE groups yet), a returning group keeps its
  position unless another holds the index, a move that would lower a channel's effective lock asks for the PIN, the
  plan probe is debug-only without ANALYZE, select-all hide counts instead of listing keys, `changes()` debounced
  300 ms, busy retry with a pause, tie-breakers and the guard test alias-agnostic.
- Final counts: core 3,187, data 354, ui 340, app 56, player 370 unit tests; I-M6-CE-1…8 on the API 30 emulator.
- Debug-seed note: seeding a playlist through `thebox-debug://guide?seed=…` into a fresh install that had already
  booted with no playlist left the guide empty (blank group chip, no rows) until the app was restarted. The setup flow
  does not take that path ("Start watching" opens the guide on All). Worth a look in M6 hardening.
- Uninstall caveat: `connectedDebugAndroidTest` uninstalls the app afterwards, which wiped the emulator's data (the
  owner's real playlist and TMDB key). The emulator now carries the fake panel's 2,000-channel playlist.
