# The guide entered from full screen opens on the playing channel

Date: 2026-11-14 (review fix pass 2026-11-15).
- The behaviour is the owner's request.
- The group rule and the consequence below are the lead's rulings.
- Details marked "choice" are the implementer's.

Owner (verbatim): "there is a small bug if i am in the guide and change to a differnt channel or section and then long press back to the cuurent channel and trhen return to the guide it is still on the old section or channel, it should always go to the current playing channel when entering the guide"

## Cause

`GuideStateMachineImpl.returnToGuide` handles Back or the Guide key in full screen, and the error card's Guide button. It called `goHome()`.
- Home is `homeRow()`: the tuned row **if the shown lineup holds it** (`tunedIndex >= 0`), otherwise the focused row.
- Nothing changed the source. So after the user moved to a group without the playing channel, the guide came back on the old group and the old row. Only the time was reset to now.
- Within one group the old code already returned to the playing row at now. The owner's "different channel" is a channel in another group.

## Behaviour

Every entry into the guide from full screen focuses the **current channel's row at now**, in a group that holds it. The current channel is the one playing, or the failed one on the error card.

The entries are:
- Back or the Guide key in full screen.
- The error card's Guide button.
- Back out of a Search opened from full screen (choice).
- The Guide key in a Settings opened from full screen (choice).

If nothing is tuned (idle, stopped), the old home applies: the shown group, the tuned row if that group holds it, otherwise the focused row, at now.

The group, in order:
1. **The group the guide shows** holds the channel. It stays, and `goHome()` focuses the row. No lookup.
2. **The group the channel was tuned from.** See "Tuned from" below.
3. **The startup source** (`initialSource`, "Open guide on": Last, Favourites or All, §3.8.4). Last is the source remembered at launch.
4. **All channels** of the channel's playlist. This is the fallback that Search and number entry already use.
5. **None holds it** (the channel is hidden, or only in locked groups): the shown group, with its focused row at now, as before.

A candidate must be listed in the guide's Groups list and not behind a parental door. Recent is never a candidate: "Recently watched" is listed in the Groups panel as a quick list (`SourceSection.RECENT`), not a group.

### The lookup (review fix 2026-11-15)

Candidates are not loaded. The machine asks once: `GuideEffects.findSourceHolding(token, candidates, rowId)`.
- `GuideEffectsImpl` asks `GuideLibrary.holds(source, rowId, access)` for each candidate in order, on IO, and stops at the first that holds the row.
- The Room library answers each with one statement: the lineup's own predicate with the id pinned and `LIMIT 1`. Recent and the group union are wrapped in a sub-select so their membership stays exact.
- `LineupQueryTest.holdsAgreesWithTheLineup` checks that `holds` and the lineup agree for every channel × source × access.
- The single answer is `GuideEvent.SourceHolding`. Then one `applySource(found, focusRowId = row)` follows, or `goHome()` when there is none.
- No candidate touches the zap list, `GuideSourceNow`, the header label, `lastNonFavourite` or the saved last source. Only the source finally applied does, exactly as a Groups pick would.

**Interim frame (choice): hold.** While the answer is pending, the screen the user is leaving stays up: full screen, or Search or Settings opened from it. The mode switches only when the answer's lineup lands, already focused on the row. So no frame of the wrong group or of an intermediate group is drawn.
- The cost is the lookup (one indexed query per candidate, usually one or two) plus that group's first lineup. A Groups pick costs the same.
- `ENTRY_HOLD_MS` (700 ms) bounds the hold. After it, the guide shows where it was, at now, and the answer still moves it unless the user acted.
- I chose this over `goHome()` at once because that draws the stale group for the whole lookup. "Jump to now" has the same problem.

**Cancellation.** A generation token (`entryToken`) is the answer's key.
- **A key while held:** the guide shows at once at its home, the key acts there, and the answer is dropped.
- **Another source change** (the user's, a relock, a deleted playlist): it wins, and the answer is dropped.
- **Leaving** (back to full screen, Search's own close, Settings): the answer is dropped.
- A late answer never overrides what the user did.
- `pendingFocusRowId` is set only together with the single applied answer. A cancelled lookup never sets it.

### Tuned from

`tunedFrom` is bound to the identity of the channel it was set for. It is set only when that channel's `Tuned` arrives for a tune that was requested from a lineup:
- the grid's OK or catch-up;
- a granted PIN tune;
- Search (the group the channel is focused in, if that lineup holds it);
- the player's channel list (`PlayerStateMachine.takeListTune`).

A refused or cancelled tune never binds: a cancelled PIN prompt drops the request. Any other channel change clears it: Previous channel, a reminder, number entry in full screen, a zap, the startup tune.

## Consequences

- **Lead ruling.** A group picked in full screen (long Left/Right, GROUP_PREV/NEXT) that does not hold the playing channel **is replaced** on the way back to the guide, per "always go to the current playing channel". If it holds the channel it stays. If its lineup is still loading it is the first candidate.
- A list tune in another group means the guide opens on that group on the way back, but only if the guide's own group lacks the channel. `docs/design/m4-player-ux.md` §3.3.1 is amended to match.
- After zapping in full screen, the tuned-from binding is gone. The guide then opens on the shown group, which is usually the zap list's, or the startup source.
- Unchanged:
  - moving inside the open guide;
  - closing what sits over the guide (Groups, the channel menu, Days, the sheet, number entry, and Search or Settings opened from the guide);
  - the short Back ladder;
  - OK in the guide (the mini player keeps the focus on that row);
  - nothing tuned.

## Tests

- `core/.../guide/state/GuideOpensOnPlayingTest`, 28 tests, on both navigators:
  - The owner's sequence: the same group, another group, the Guide key, scrolled in time.
  - Every case of the group rule.
  - Search and Settings opened from full screen; the error card.
  - Overlays over the guide do not refocus.
  - OK in the guide keeps the focus.
  - Stopped (no lookup).
  - Exact effect logs: the normal case, a stale tuned-from group, a hidden channel. A deferred-answer mode checks that the frame is held.
  - A key, a source change or leaving while the lookup is pending; the timeout.
  - Tuned-from cleared by Previous channel, a reminder and a number tune in full screen.
  - Green returns to the last group after a search that found nothing.
- `core/.../full/state/ListTuneGuideGroupTest`: a list tune binds its group; a refused list tune does not.
- `data/.../LineupQueryTest.holdsAgreesWithTheLineup`.
- `ui/.../GuideEffectsImplTest.findSourceHoldingAnswersTheFirstHolderOnce`.
- `ui/.../GuideViewModelTest.theGuideFromFullOpensOnThePlayingChannel` (real view model and feeds).
