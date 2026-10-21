# Channel editor: code review, core machine, shell hooks, UI and app (2026-10-18)

Scope: the uncommitted working tree for M6 "Channel editor", excluding `data/` (another reviewer has it).
- Core: `core/.../editor/*` and its tests, `editor_keys.csv`.
- Settings: the `SettingsMachineImpl` / `SettingsPages` hooks and `ChannelsRows.kt`.
- Guide: `GroupMenuItem.EDIT` in `GuideStateMachineImpl.kt`.
- UI: `ui/.../editor/*` and its tests, `strings_editor.xml`, and the touched `SettingsScreen`, `SettingsRowViews`, `ConfirmCard`, `PlayerParts` and `AppRoot`.
- App: `MainActivity` and `ChannelEditorOnDeviceTest`.

The review checks the code against design §2.4–§2.6, §3.1–§3.4, §5 and §6.1/§6.3, both design reviews, the decision note and
CLAUDE.md. No code was changed. Gradle and the emulator were not run; every finding comes from reading the code, with
file:line references.

**Verdict: no BLOCKER. Fix the 9 SHOULD-FIX items before the owner sees it. The NITs can wait.**

The machine is in good shape:
- The key tables, the Back ladder and the FIFO match the design.
- Stale versions and tokens are dropped.
- The shell hooks call each callback exactly once on every path I traced.
- The Compose code keeps the engineering rules: no graphicsLayer, blur, Popup or Dialog, and no nested lazy lists.
  `.dp` is design px under `BoxDesignScale`, as in the rest of Settings.

The issues are all at the edges: a failure state that shows the wrong message, a race between two reloads, a write
runner that can die, one move-mode case, and some wrong reason texts.

---

## What was verified (no action)

- **Keys (§2.4).**
  - `act` returns `false` only for BACK at the bottom of the ladder, OPEN_GUIDE and EXIT. While closed it returns
    `a != BACK`, and once `gone`, only BACK leaves (`ChannelEditorMachineImpl.kt:180-198`).
  - Unlisted keys are consumed, and ← in the Groups pane is consumed (`:239`).
  - Every layer ignores CONTEXT_MENU and digits.
  - ← closes the menu, the picker and the choice list. The menu clamps with no wrap, and CH± jumps to the first or
    last line (`:493-497`).
- **Back ladder (§2.5).** Every rung is present, in order:
  - rungs 1–3 by layer dispatch (`:186-191`);
  - rungs 4–8 in `back()` (`:366-377`). Digits are cancelled first, which is harmless.
  - The reachability test checks "at most 12 BACKs" from every state it explores.
- **Selection scope.** `switchList` and `setFilter` clear channel ticks with `N_UNSELECTED`; `toggleHiddenGroups` clears
  group ticks. After a `Written(Done)`, only the ticks that write acted on are cleared, scoped by `(list, filter)`
  (`:983-1005`).
- **FIFO.**
  - The bound of 8 holds. A full queue keeps the picker open and the block lifted (`:690`, `:769`).
  - `EditorWriteQueue.complete` matches the oldest equal entry, and the runner is a single-consumer `Channel`, so no
    write is merged or applied twice.
- **Deadlines.** `nextDeadlineMs` is the minimum of the notice, the digit and the follow deadlines, and every one of
  them is cleared on its Tick (`:1251-1255`, `:1404`). The 150 ms follow restarts on each move. RIGHT / OK / Edit
  channels follow at once.
- **Stale events.**
  - `Groups`, `Channels`, `GroupRows`, `ChannelRows`, `Summary` and `Located` are checked by version, token, list or
    number.
  - On the view-model side, a newer load cancels the older one (`ChannelEditorViewModel.kt:192`, `:240`), and page
    reads check the version (`:217`, `:262`).
  - The one gap is `LoadFailed` from `summarize` (S4).
- **Shell hooks (§3.2).**
  - `editText`, `editNumber` and `requestPin` each fire exactly once: on commit (unchanged text included), on cancel,
    on pad Back over empty digits, on a newer request, and from `closeOverlays` (`:996-1006`).
  - `closeOverlays` runs for OPEN_GUIDE, EXIT, push, pushGated, replace, pop, popTo, onLeave, leaveToGuide,
    startCapture and open. `gated()` stales a pending ext PIN (`false`).
  - `onPinResult` reports both outcomes.
  - `PinGate.request` never answers synchronously for `SettingsAction`. It always opens the pad when a PIN is set
    (`GuideStateMachineImpl.kt:859`), so the callback is stored before any answer can arrive.
  - The `keyHandler` move to after the overlays (`SettingsMachineImpl.kt:179-188`) does not change the phone page,
    which never opens the settings pad, confirm, menu, move or choice.
- **Guide.** EDIT is the last item and goes through `openSettingsGated`. It is offered only on `SourceSection.GROUP`
  entries (`GuideStateMachineImpl.kt:1359`), so the non-Group no-op in the `?.let` is unreachable.
- **Rows (§3.1).** `channels.editor` is first and SIMPLE; `channels.hidden` is ADVANCED and emitted only when `advanced`.
- **View model.**
  - Writes run in the singleton `@EditorWriteScope` (SupervisorJob), so `close()` cancels loads, summaries, locates and
    the change feed, but no write. The test covers this (`ChannelEditorViewModelTest.kt:164`).
  - Every repository call is in `withContext(io)`.
  - Every result is posted with `launch(Dispatchers.Main)`, which is not `.immediate`, so nothing re-enters the
    machine.
  - Access is collected from `init`, so it reaches the machine before `open`.
- **Compose.**
  - No graphicsLayer, blur, shadow, Popup or Dialog in `ui/editor`.
  - The only `clip` is inside `ChannelLogo`'s image, and the editor is a sibling of the surface (HoleMode.COVERED), so
    that is fine.
  - The two LazyColumns sit side by side, not nested. The picker is a plain Column of at most 8 rows.
  - `open` / `close` run in a `DisposableEffect`, not in the composition body.
  - Only visible rows are composed, plus the 0.2-row partial.
  - Strong skipping keeps unchanged row instances from recomposing (S/N on lambdas below).
- **Tests.** They assert behaviour, not just "no crash":
  - The key table checks pane, layer, consumed and a typed check per row, including press-budget assertions on the
    exact `EditorWrite`.
  - The reachability alphabet includes CONTEXT_MENU, which is long OK: `DefaultKeyMap.kt:28` maps long DPAD_CENTER to
    CONTEXT_MENU, so long OK is covered.
  - The second reachability run, without Menu, reaches every channel action.
  - The back-ladder, queue, selection, paging, summary, parental and text tests each pin the §6.1 behaviour they name.

---

## SHOULD-FIX

### S1. A Groups load failure shows "Press OK to try again" over the channel list, and OK then ticks a row you cannot see
- **Where.**
  - `ChannelEditorMachineImpl.kt:1153`: `failed.isNotEmpty() -> E_LOAD` sets the **channels** pane's `empty`, whichever
    pane failed.
  - `ChannelEditorScreen.kt:358-366`: when `emptyText != null`, the rows are not drawn.
  - In the channels pane, OK retries only `EditorPane.CHANNELS in failed` (`:254`). Otherwise it calls `tickChannel()`
    on the focused, invisible row (`:256`), and → still opens the channel menu on it (`:259`).
- **Effect.** After a `LoadFailed(GROUPS)`, which is also sent for a failed group page (`ViewModel:232`), the user:
  - reads "Press OK to try again" in the channels pane;
  - presses OK, which ticks an invisible channel;
  - can then Hide or Lock it from the menu, all without seeing the row.
- **Fix.**
  - Set `empty = E_LOAD` only when `CHANNELS in failed`.
  - Show the groups failure in the Groups pane: a `PaneMessage` under the head rows, with `groupsFailed: Boolean` in
    the state.
  - Make OK retry every failed pane from **either** pane (`retry()` already reloads both).
  - Add a key-table row: G load failed, channels pane, OK → no tick, a reload issued.

### S2. The change feed's reload races the machine's `keepKey` reload, so the cursor may not follow the row after Sort, Reorder or Move
- **Where.**
  - After `Written(Done)` the machine calls `reloadAll()` with `keepKey` = the focused key (`:1007`, `:1038-1043`).
  - The same write triggers `repo.changes()`. After `CHANGE_GAP_MS` (100 ms), `reloadAfterChange()` calls
    `loadChannels(…, keepKey = null)` (`ChannelEditorViewModel.kt:152-157`, `:337-340`).
  - `loadChannels` **cancels** the in-flight job (`:240`).
- **Effect.** When the machine's list read plus `indexOf` takes more than about 100 ms (thousands of channels, or All
  channels), the keyed load is cancelled. The cursor then stays at the old index, so after a drop or a sort the focus
  lands on a different channel. The order of the feed and `Written` is not deterministic either.
- **Fix (pick one).**
  - Route the feed through the machine: a `ChannelEditorEvent.Changed` makes the machine reload with its own `keepKey`
    and `pendingCursor`.
  - Or: while a keyed load is in flight, the feed reload is skipped or coalesced into it.
- **Test.** Add a VM test: a slow `channels()`, a write, the feed fires, and the `Channels` event still carries the
  `cursorIndex`.

### S3. The write runner can die, which leaves `pendingWrites` stuck and silently stops every later write
- **Where.** `EditorWriteRunner.submit` (`ChannelEditorViewModel.kt:369-383`).
  - A `CancellationException` from `repo.write` (for example a `TimeoutCancellationException` from a future
    `withTimeout`, or a cancelled Room transaction) is rethrown.
  - That ends the `for (p in queue)` loop for good, while `started` stays `true`.
  - `p.done` is never called for that write.
- **Effect.**
  - The machine's queue never drains: "Saving…" forever, then "Still saving" on the 9th action.
  - Every write already queued in the channel never runs, until the process restarts.
- **Fix.**
  - Rethrow only when the runner's own scope is no longer active: `if (!coroutineContext.isActive) throw c`; otherwise
    map it to `Failed(STORAGE)`.
  - Call `done` in a `finally` with `Failed(STORAGE)` if no result was produced.
- **Test.** A repository that throws `CancellationException` once: the next write still runs, and both get a
  `Written`.

### S4. A summary failure blanks the channel list, unversioned, and leaves the menu loading
- **Where.** `ChannelEditorViewModel.kt:288-291` sends `LoadFailed(EditorPane.CHANNELS)` when `summarize` throws. The
  machine then sets `cWin = null` and `E_LOAD` (`:1127-1130`), while the menu stays `loading = true`; OK is ignored
  while loading (`:499`).
- **Problems.**
  - The event carries no token, and the summary job is not cancelled when the menu closes. A late failure after the user
    closed the menu and switched lists wipes the **new** list.
  - A read failure of a count should not look like the list failing to load.
- **Fix.**
  - Add `ChannelEditorEvent.SummaryFailed(token)`. On a current token, close the menu and show the `E_LOAD` notice (or a
    menu line "Couldn't count the selection… Try again").
  - Cancel the summary job in `closeMenu()`; the view model keeps a `summaryJob`.

### S5. A row that stays null makes the machine reload the list in a loop
- **Where.** `ChannelEditorMachineImpl.kt:1107-1111`: any `null` in `ChannelRows` calls `reloadChannels()`. The new
  version re-requests the same pages, and if the id still resolves to null, it reloads again.
- **When.**
  - The list query and `channelRows`' `ROW_SELECT` disagree about a row: a join or filter mismatch, or a row whose prefs
    or group join drops it.
  - A refresh that keeps deleting rows.
  - Each loop is a full list query plus `indexOf` with no back-off, on the user's main path.
- **Fix.**
  - Reload at most once per window version (`win.reloadedForNull`).
  - Otherwise store the non-null rows and draw a placeholder for the null slots.
  - Log once at WARN (class only, no names).

### S6. Dropping a gathered, non-contiguous block where it was lifted writes nothing, and the gather is lost
- **Where.** `dropMove` (`:758-760`): `if (m.index == m.fromIndex) { endMove(); return }`.
  - For ticks at 0 and 2 with focus at 3, the block is gathered at index 1 (`EditorMoves.gather`), and `fromIndex == 1`.
  - Pressing OK straight away shows the block "dropped", then the rows scatter back.
  - E5 is "tick several, Reorder, OK" to bring them together.
- **Fix.** Skip the write only when the block was already contiguous at that place:
  `moveSources == (m.fromIndex - offset until m.fromIndex - offset + size).toList()`. Otherwise emit the reorder.
- **Test.** Add the case to `ChannelEditorMoveModeTest.nonContiguousTicksLiftAsOneBlockAndDropOnce`.

### S7. Wrong reason texts on disabled Reorder (reason and next step)
- **Groups pane.** `EditorMenus.groupLines` (`EditorMenus.kt:109`) disables Reorder under "Hidden groups: Not shown"
  with `D_REORDER_FILTER`, "Set Show to All to reorder."
  - The Groups pane has no Show filter, so the next step points to a control that does not exist.
  - Either enable it (`ReorderGroups.showHidden` already exists for exactly this, `ChannelEditorContracts.kt:163-164`),
    or add `D_REORDER_HIDDEN_GROUPS`: "Set Hidden groups to Shown to reorder."
- **Locked-out channels.** `reorderLine` (`:95`) uses the same `D_REORDER_FILTER` when `lockedOut > 0`, where the real
  cause is the locked channels left out. Add `D_REORDER_LOCKED`: "Some channels are hidden by the PIN. Enter your PIN to
  reorder this group."

### S8. Wrong or run-on notice and confirm texts
- **Group sort confirm.** `choose(GROUP_SORT)` (`:852-858`) reuses the channel-group confirm: "Sort Groups?" (a
  mid-sentence capital) with "Your own order for this group is replaced." (`strings_editor.xml:138-141`), and the
  provider form reads "Your own order for Groups is removed."
  - Add `C_SORT_GROUPS_TITLE` "Sort the groups?", `C_SORT_GROUPS_BODY` "Your own group order is replaced. You can
    reorder again at any time." and `C_PROVIDER_GROUPS_BODY` "Your own group order is removed."
  - Use them in `confirmTexts` for a group kind: add `ConfirmKind.SORT_GROUPS` / `PROVIDER_GROUP_ORDER`, or pass the
    pane.
- **Show with rule-hidden channels.** `shownNotice` (`:620-623`) joins `N_SHOWN` and `N_SKIPPED_RULE` with a space:
  "3 channels shown 2 stay hidden by a rule. …".
  - Either add a period to `N_SHOWN`'s quantity strings when joined, or (better, and one translatable unit) add a
    `N_SHOWN_SKIPPED` plural taking both counts.
  - The test that pins the joined form (`ChannelEditorWriteQueueTest.kt:78`) changes with it.

### S9. A list whose channels are all locked out shows "no channels yet" or "No channel matches"
- **Where.** `updateEmpty` (`:1156-1165`) looks only at `win.count == 0`. In All channels with the session locked and
  every channel in a locked group, the pane shows the locked-out note **and** E_NO_CHANNELS ("This playlist has no
  channels yet. Refresh it…"). The two contradict each other, and the refresh advice is wrong. A Find whose matches are
  all locked out says "No channel matches".
- **Fix.** When `count == 0 && lockedOut > 0`, set `empty = null` (the note row alone, focused, with OK → PIN), or use a
  dedicated `E_ALL_LOCKED_OUT`. Add a parental test.

---

## NIT

- **N1. Group row value.** It reads "120 · 8" (`EditorRows.kt:214`); §2.2 and §2.6 say "120 · 8 hidden". The bare
  pair is ambiguous at 10 feet. Add `GROUP_VALUE` (P) "%1$d · %2$d hidden". `ChannelEditorScreenTest` pins "6 · 1", so
  update that test too.
- **N2. "0 hidden" in counts.** `GROUPS_COUNT` / `CHANNELS_COUNT` always print it ("6 groups · 0 hidden"). Use a second
  key without the hidden part when `hidden == 0`.
- **N3. `playlistName` is always "".** The machine publishes `playlistName = ""` (`:1383`). The breadcrumb gets the name
  from `ChannelEditorPageSource`, so either drop the field or fill it.
- **N4. The queue does not survive a revisit.** `close()` clears the machine's queue (`resetAll`, `:1350`) while the
  runner still holds the writes. A quick re-entry shows no "Saving…", and the bound of 8 counts per visit only.
- **N5. Lost input on a full queue.** A Rename text or a confirmed Reset is dropped with only "Still saving"
  (`onText` → `enqueue`). That is acceptable, but a Rename could keep its text: re-open the editor with it.
- **N6. Wall-clock deadlines.** `nowMs = System::currentTimeMillis` (`ViewModel:91`). If the clock steps back (NTP
  after boot on boxes without an RTC), the Tick arrives "early", nothing clears, and `nextDeadlineMs` does not
  re-emit. A notice then never fades and digits never commit. This copies `SettingsViewModel` (pre-existing). Prefer
  `SystemClock.uptimeMillis`, or re-arm in the collector (`while (nowMs() < at) delay(at - nowMs())`).
- **N7. `requestPin` without a gate.** With `pinGate == null` it grants (`SettingsMachineImpl.kt:341`), as `gated()`
  does. That is fine in production (the gate is always wired), and the data layer's `refusesLowering()` is the backstop.
  Consider `false` for a null gate.
- **N8. Comment vs order in `closeOverlays`.** The comment (`:1001-1002`) says a callback that opens another overlay is
  not undone. That holds for text → anything, but a text callback that opened a pad would have the pad's callback
  fired with null at once while the pad stays open. The editor never does this. Reword the comment, or snapshot all
  three callbacks before invoking any.
- **N9. Unused key.** `D_SINGLE` is never shown (Rename and Set number are omitted, not disabled). Keep it or drop it.
- **N10. Literal accessibility strings.** "checked" / "unchecked" are English literals (`EditorRows.kt:155`,
  `EditorMenuCard.kt:189`). `RULE` and "…" are in `strings_editor.xml` outside `EditorText`, which is acceptable for
  view-only marks.
- **N11. Recomposition.** The `RowsArea` item lambda and the `Toolbar(focused = { … })` lambda capture the whole `ui`,
  so every state change (a notice fade, `pendingWrites`) re-runs the visible rows' item bodies. The row composables skip
  on equal instances, so the cost is small. If it shows up in the 2,000-channel profile, pass `cursor`, `top`,
  `selection` and `move` instead of `ui`.
- **N12. Move mode and a background reload.** During move mode, a background reload (refresh, or a Move written just
  before) changes `count`, but `moveSources` and the stale tick indices on evicted pages stay as lifted, so other rows
  can draw in the wrong place until the drop. Consider cancelling move mode when the list's count changes.
- **N13. Non-exhaustive plural map.** `ResourceEditorTexts.pluralId` ends in `else -> throw`, so a new plural key
  without a mapping fails only at runtime. `ResourceEditorTextsTest` catches it; an exhaustive `when` would catch it at
  compile time.
- **N14. Confirm button side.** Cancel is on the right and focus starts there, so the deliberate step is ← then OK.
  Design review 2's ruling (c) says "→ then OK". The code matches the settings `ConfirmCard`; fix the sentence in the
  design.
- **N15. `locate` errors.** A storage error in `locate` reads as `null`, so the user sees "No channel 501 in this list"
  (`ViewModel:303-305`). Show `E_LOAD` instead.
- **N16. Test gaps (small).**
  - Key table: no `TOGGLE_FAVOURITE` row; no hold-repeat row in move mode ("hold repeats", §2.4); no "OK on the note
    row with count 0" row.
  - View model: no test that `close()` stops an in-flight load from reaching the machine, and no test of the runner's
    failure path (S3).
  - `everyChannelActionIsReachedWithoutTheMenuKey`'s last assertion (`assertFalse("menu" !in cov.layers)`) is
    trivially true: drop it or assert something.

---

## Fixes recommended before the owner sees it (in order)

1. **S1**: show `E_LOAD` only for the failed pane, and make OK retry from both panes (no invisible ticks).
2. **S3**: make the write runner survive a `CancellationException` and always answer `done`.
3. **S2**: route the change feed through the machine (or coalesce it) so `keepKey` survives the write's own reload.
4. **S6**: write a gathered block that is dropped in place.
5. **S4**: add `SummaryFailed(token)`; stop treating a count failure as a list failure; cancel summaries on menu close.
6. **S5**: cap the null-row reload at once per version.
7. **S9**: fix the all-locked-out empty state.
8. **S7** and **S8**: correct the Reorder reasons (groups, locked-out) and the group-sort and Show-with-rule texts.
9. Optionally N1 ("120 · 8 hidden") and N2 (no "· 0 hidden"), since the owner will see them on the first screen.
