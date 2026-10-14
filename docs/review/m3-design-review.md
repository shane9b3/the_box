# M3 guide design: adversarial review

Reviewer pass over `docs/design/m3-guide.md` (proposed 2026-10-14, 2,111 lines, read in full), done 2026-10-14 on `main` at `9a87271`. Nothing in the design doc was edited; every fix below is text for the lead to apply.

Checked against: `CLAUDE.md`; `00-master-plan.md` §2–§6, §8; `01-design-reference.md`; `design/TV Guide.dc.html` (markup and script read as text); `review/architecture-review.md` B1, B2, C, D, E; `review/product-review.md` §2.1–§2.4, §3, §4.3–§4.8, §4.12; `decisions/2026-10-14-settings-and-branding.md`; `design/m3-compose-tv-api-notes.md`; `design/m1-data-layer.md` §2.2, §2.5.3; `design/m2-playback-core.md` §2.6, §2.7, §3.9, §3.11, §3.14, §3.15; `research/protocols-m3u-xmltv-xtream.md` §2.6.

Code read: `core/.../epg/EpgContracts.kt`, `core/.../epg/store/{EpgReaderImpl,FtsSupport}.kt`, `core/.../catchup/CatchupContracts.kt`, `core/.../playback/PlaybackContracts.kt`, `core/.../common/Hash64.kt`, `data/.../db/dao/{ChannelDao,GroupDao,ReminderDao}.kt`, `data/.../db/entity/ReminderEntity.kt`, `data/.../db/LibraryDatabase.kt`, `data/.../repo/{ReminderRepository,RoomMergeSink,SyncStatusRepository,DataSettings,ChannelRepository,PlaylistRepository}.kt`, `data/schemas/…/2.json` (parsed), `player/.../api/PlaybackApi.kt`, `ui/.../playback/VideoLayer.kt`, `ui/.../theme/BoxDesignScale.kt`, `ui/build.gradle.kts`, `gradle/libs.versions.toml`, `tools/fakepanel/README.md`. One library source checked: `androidx.sqlite:sqlite-jvm:2.6.2` sources from the Gradle cache (`SQLite.kt:60-67`).

Line numbers for the design are `m3-guide.md:N`. No Gradle runs, no device.

## Summary

| | Count |
|---|---|
| A. Blockers | 8 |
| B. Should fix before commit 0 | 16 |
| C. Nice to have | 18 |
| D. Claims verified correct | 30 |
| E. Lead decisions (§7 items 1–16 plus 7 added) | 23 |

The core bets hold:

- one draw node over a toolkit-agnostic canvas;
- virtual focus with JVM key tables;
- a windowed pager on its own thread with generation-safe refs;
- hole geometry computed from metrics, not from layout callbacks;
- a catch-up seam that computes no times of its own.

The blockers are lifecycle and staleness problems the design does not see:

- the only key-handling node leaves composition in full screen;
- the Back ladder loops;
- snapshot arrays are recycled while main may still draw them;
- a text-fit cache survives an EPG rebuild and can index past the end of a string;
- row blocks never reload when EPG matching finishes;
- the 7-day programme search is starved by week-old airings;
- commit 0 lacks four `:core` feeds that `:ui` and `:core` code needs, because the data lives in `:data`;
- the ban on layers on the grid host makes every scroll frame re-record the whole window.

---

## A. Blockers

### B1. The key-handling focus target leaves composition in FULL and SEARCH; key wiring is specified three different ways

- **Where:** §0.3 (`m3-guide.md:16`), §3.6.5 (`:1477`), §3.18.1 (`:1744`), §3.13.1 (`:1637`), §1.2/§1.4/§1.5 ownership.
- **Problem (1).** §3.6.5 puts `focusRequester`, `focusable()` and `onPreviewKeyEvent` on `GuideGridHost` (WP-A). §3.18.1 says `KeyRouter` is attached "on the guide root (the single focus target)". §0.3 says the guide screen is one focus target. Three WPs (A: host, C: `AppRoot`/`FullScreenHost`, D: `KeyRouter`) will each assume another owns it.
- **Problem (2).** Worse, the root tree swaps screens with `when (ui.mode) { GUIDE -> GuideScreen … FULL -> FullScreenHost … }` (`:1637`). When the mode becomes FULL, `GuideScreen` and its grid host leave composition and the focused node is gone.
  - Compose dispatches keys only to a focused node's `KeyInput` chain: "a node with `onKeyEvent` but no focus never sees keys" (API notes §2.1, `m3-compose-tv-api-notes.md:79`).
  - In FULL, the §3.4.5 table (zap, Back to guide, group cycle) therefore never runs.
  - BACK falls through to the Activity, which finishes. The user tunes, presses OK for full screen, and the next Back exits the app.
- **Problem (3).** SEARCH has the inverse problem. A root-level `onPreviewKeyEvent` sees every key before the `BasicTextField` (pre-visit runs root → focused, `:74`), so it would steal D-pad and Back from the text field unless it passes through in SEARCH.
- **Evidence:** `m3-guide.md:1477`, `:1744`, `:1637`; API notes `:74-80`.
- **Fix.** Replace the host sentence in §3.6.5 and the last sentence of §3.18.1 with the text in F5:
  - one key sink at the `AppRoot` level, outside the `when`, so it survives every mode change;
  - owned by WP-D (`KeyRouter` + `Modifier.boxKeySink`), placed by WP-C;
  - it returns `false` for every key while `mode == SEARCH`, except BACK when the field is empty and has no IME;
  - `requestFocus()` from a `LaunchedEffect(mode)`, on `Lifecycle.ON_RESUME`, and after SEARCH closes;
  - `GuideGridHost` carries no focus or key modifiers.
- **WPs:** A, C, D (contract change in commit 0).

### B2. The Back ladder loops between GUIDE and FULL; exit is unreachable while anything plays

- **Where:** §3.4.6 (`:1362-1368`), §3.4.5 BACK (`:1353`), I-8 (`:1897`).
- **Problem.** GUIDE at home with a tuned channel: Back → FULL (step 4). FULL: Back → GUIDE, focus restored (`:1353`). GUIDE at home: Back → FULL again, forever.
  - Step 5 (exit) is only reached when nothing plays.
  - The default key map binds no EXIT key (`:1746-1766`), so with five keys the user cannot leave the app while watching.
  - I-8 expects "Back: now → FULL → exit hint → exit" (`:1897`), which neither table produces. WP-D will build one and test the other.
- **Evidence:** product nav map `product-review.md:136` ("Back ─► GUIDE (player keeps playing in mini) ─► Back ─► 'Press Back again to exit'"). Product S7 (`:235`) and §3.9 (`:410`) read as the same ladder, without the return leg.
- **Fix.** §3.4.6 step 4 becomes: "Something is tuned and the previous transition was **not** FULL → GUIDE by BACK → FULL." §3.4.5 BACK becomes: "GUIDE, viewport MINI, focus **home** (tuned row at now); set `returnedFromFull = true`. Any action other than BACK clears it." I-8 becomes: "Back ×1 from a scrolled grid → home; Back → FULL; Back → GUIDE (home); Back → exit hint; Back → exit." Add a `BackLadderTest` row for the loop.
- **WP:** D.

### B3. Snapshot arrays are recycled while main may still be drawing them

- **Where:** §3.2.3 "Snapshot" (`:1249`).
- **Problem.** "Snapshot arrays are recycled from the snapshot published two swaps earlier (main may still hold the previous one)."
  - Publication is `StateFlow.value = data`, which conflates. The VM forwards to `dispatch` on main. Nothing tells the pager what main currently holds.
  - If main is late by one publication (a GC pause, a 50 ms frame), `frame.data` is still N−1 while the pager writes N+1 into N−1's arrays.
  - The renderer and navigator then read `rowFirstCell`/`rowCellCount` from one snapshot against `cellStart` of another. The result is torn drawing or `ArrayIndexOutOfBoundsException` on main.
- **Evidence:** `m3-guide.md:1246-1249` (coalesced, latest wins; no acknowledgement); `GuideWindowData` is "never mutated after publication" (`:299`), which recycling violates.
- **Fix.** Allocate every snapshot fresh. Worst case (5-minute programmes, 18 rows × 9 h ≈ 1,950 cells × ~100 B) is about 200 KB, about 5 per second during a hold, all off-main. Replace `:1249` with the F1 text. If recycling is ever wanted, gate it on an `AtomicLong releasedSeq` written by main when it replaces `frame.data`.
- **WP:** B.

### B4. `TextFit` is keyed by generation-local `textId` and by `rowId`: a stale cut length crashes `drawText` after an EPG rebuild or a rename

- **Where:** §3.6.4 (`:1458`), §4.3 (`:1806`).
- **Problem (1).** The cache maps `(textId, px) → n` and the renderer draws `title[0, n)`. `textId` is "generation-local" (`:323`).
  - After a generation swap the same id names another title. A hit returns an `n` computed for a longer string, and `Canvas.drawText(String, start, end, …)` throws `IndexOutOfBoundsException` on the main thread.
  - EPG rebuilds happen daily while the app is open. The pager clears its own width cache on generation (`:1248`), but nothing clears `TextFit`.
- **Problem (2).** Channel names use the same cache "keyed by `rowId`". `rowId` survives a custom-name edit or a `display_name` recompute that shortens the name, giving the same crash.
- **Evidence:** `m3-guide.md:323`, `:1248`, `:1458`.
- **Fix.** §3.6.4: "Entries are keyed by `(textKey, availPx)` where `textKey = (generation shl 32) or textId` for titles and `Hash64.str(rowId, name)` for names. The renderer clears `TextFit` when `frame.data.generation`, `patchSeq`, `metricsScale` or the clock form changes. It always draws `min(n, text.length)`." Add a `GuideRendererTest` case: swap the generation with the same `textId` and a shorter title; no exception, correct cut.
- **WP:** A.

### B5. Row blocks never reload when a row's content changes but the lineup's membership does not

- **Where:** §3.2.1 re-emission (`:1234`), `GuideRowsCache` (`:657-666`), §3.4.9 (`:1384-1385`).
- **Problem (1).** The lineup `version` is the hash of the `LongArray`, and "an unchanged version is not emitted". Row blocks hold `GuideChannel` values: name, number, logo, archive days, EPG ref and shift. They are reloaded only when evicted.
- **Problem (2).** These all change without membership changing:
  - the matcher writing `epg_match` after the first EPG import, which turns `epg = null` into a ref;
  - an `epg_mapping` fix;
  - an EPG source priority or shift change;
  - a custom name, number or logo;
  - the `display_name` recompute;
  - a refresh that changes catch-up attributes.
- **Problem (3).** The tracker list (`:1234`) does not even include `epg_match`, `epg_mapping` or `epg_source`. On first run the playlist imports, the guide opens with every row `NO_EPG`, and the EPG import and matching finish. The visible rows keep showing "No information" until their block is evicted.
- **Evidence:** `m3-guide.md:1234`, `:1236`; `GuideChannel.epg` is resolved once per block load (`:1236`, `:1241`).
- **Fix.** Add a row-content invalidation, contract text in F3:
  - `GuideLibrary.rowContent: Flow<Long>` emits on any change of `channel`, `channel_prefs`, `playlist`, `epg_mapping`, `epg_match` or `epg_source`, debounced 500 ms.
  - `GuideRowsCache` reloads its **loaded** blocks in place and bumps `version`.
  - The pager re-queries rows whose `(epg, epgShiftMin)` changed.
  - `LineupQueryTest`/`RowBlockTest` gain "matcher writes `epg_match` → loaded block re-emits with the ref".
- **WP:** B (contract change in commit 0).

### B6. Programme search with the 7-day look-back returns the oldest airings of the best text and starves on-now and upcoming hits

- **Where:** §2.7 (`:981-992`), §3.10.2 (`:1551-1557`).
- **Problem.** `searchRanked` takes texts by bm25, then for **each text** takes its airings `ORDER BY p.start_min` up to `limit − hits.size` (`EpgReaderImpl.kt:146-199`, `FtsSupport.kt:52-55`).
  - With `?2 = pastFromMin − 360` and a 7-day look-back, a text such as "BBC News" (hourly on many channels) fills all 200 hits with airings from seven days ago, oldest first.
  - §3.10.2 then drops ended hits outside each channel's archive. Most channels have no archive, so the programme row shows a handful of week-old items and nothing on now or later.
  - The FTS4 one-statement form (`FtsSupport.kt:59-66`) has the same shape.
  - I-11 ("news" → programme rows) would show the failure.
- **Evidence:** `EpgReaderImpl.kt:162-167` (limit per text = remaining budget, ascending start); `FtsSupport.kt:52-55`.
- **Fix.** Do not overload `search`. Keep it unchanged for current/upcoming, plus the CONTINUATION filter. Add `searchPast` (F2):
  - airings with `end_min ∈ (pastFromMin, nowMin]`, ordered `start_min DESC`;
  - at most `perText` (5) airings per text;
  - its own `limit` (100).
  - `GuideSearchImpl` calls `search(q, now, 150)` then `searchPast(q, now, now − min(7, D) × 1440, 100)` and merges before ranking.
  - Add `perText` (8) to `search` too, so one daily show cannot fill the upcoming budget.
  - The `GuideSearchTest` fixture gets an hourly title on 20 channels over 14 days, and asserts at least one ON NOW and one UPCOMING hit.
- **WP:** B (contract change in commit 0).

### B7. Commit 0 lacks the `:core` feeds that `:ui` and `:core` consumers need from `:data`; one contract file has a WP body

- **Where:** §0.16 (`:29`), §3.16.1 (`:1707`), §3.3.4 (`:1287-1289`), §2.4 `DeviceClass` (`:723-727`), §5.5 harness (`:1886`), §2.5 `GuideEvent.FocusChannel` (`:829-830`), §2.9.2 (`:1066`).
- **Problem.** "`:ui` never sees `:data`" (`:29`), yet:
  1. `GuideViewModel` is "injected with … the sync status" (`:1707`). `SyncStatus`/`SyncStatusRepository` are `:data` types (`SyncStatusRepository.kt:19-36`) and depend on WorkManager.
  2. `GuideLimits` (WP-D, `:core`) needs `DataSettings.epgPastDays`/`epgFutureDays` (`:1287-1289`). These are `:data` types (`DataSettings.kt:14-30`) and are not in `GuideSettings` (`:697-708`).
  3. "A `DeviceClass` provider" (`:1707`) is bound by no Hilt module in §1.2–§1.5.
  4. I-1…I-16 read "a Hilt `@EntryPoint` exposing the state machine's `ui` and `frame`" (`:1886`). The state machine is created per ViewModel (`:1707`), so no singleton exists for an entry point to expose, and no WP owns the holder.
  5. The state machine owns `mode` but has no event for "SEARCH closed without a channel" or "search tuned a channel, go FULL": `FocusChannel` (`:830`) covers one case only. WP-C's Search screen cannot leave SEARCH.
  6. `NamePrefixRule.parse` has a `TODO("WP-B")` body inside a commit-0 contract file (`:1066`). This breaks "No WP edits … a contract file" (`:40`).
- **Fix.** Add to commit 0, text in F4:
  - `core/.../guide/GuideEnvironment.kt` with `GuideDataWindow` (past/future days flow), `SyncFeed` (a `:core` `SyncChip`-level model) and `DeviceClassSource`, all bound in `GuideDataModule` (WP-B) or `:app`;
  - `GuideDebugProbe` (a `@Singleton` holder the VM registers its machine with; debug-only reads);
  - replace `GuideEvent.FocusChannel` with `SearchClosed(channel, tuned)`;
  - move `parse` to `ChannelNamePrefixImpl.kt` as `NamePrefixRules.parse`.
- **WPs:** B, C, D.

### B8. Without a layer on the grid host, every scroll frame re-records the whole window

- **Where:** §3.6.5 (`:1477`: "No `graphicsLayer` on it or any ancestor"), §5.3 `RootTreeRuleTest`.
- **Problem.** The surface rule binds **ancestors of `VideoLayer`** only (CLAUDE.md; API notes `:176-177`: "Descendants of those layers are fine. Also fine: layers on siblings drawn above the surface"). `GuideGridHost` is a sibling inside `GuideScreen`, not an ancestor.
  - A draw node without its own layer records into the nearest ancestor layer: Compose's coordinator invalidation walks up to the first coordinator with a layer, which here is the `AndroidComposeView` root.
  - So each `frameVersion` bump and each `Animatable` frame (200 ms at 60 fps on every move) re-runs the draw of every non-layered node in the window: the ambient `drawImage` (two bitmaps during a cross-fade), `HoleChrome`'s path clip, every `Text` in the header, panel and footer, and the mini overlays.
  - On an A53 that is roughly 0.5–2 ms of main-thread recording per frame, during the exact frames the P90 < 16.7 ms gate measures. This is on top of the renderer's ≤ 2 ms budget (`:1793`).
- **Evidence:** `m3-guide.md:1477`; API notes `:175-177`. The layer-scope behaviour is Compose internals I know but did not re-read in the 1.12.1 sources. WP-A confirms it with a `Trace` section around the panel's draw, which should not fire on a grid-only frame.
- **Fix.** §3.6.5 replacement in F6:
  - `GuideGridHost` gets `Modifier.graphicsLayer()` with default parameters (no alpha, no clip, no shape, no effect): a plain RenderNode, no offscreen buffer;
  - `GuideScreen`'s header/panel column and `AmbientBackground` each get one too (both are siblings of `VideoLayer`);
  - `RootTreeRuleTest` asserts "no layer modifier on any **ancestor** of `VideoLayer`" and, separately, "a layer modifier **on** `GuideGridHost`";
  - §6.3's convention already reads "ancestor", so it stays.
- **WPs:** A, C.

---

## B. Should fix before commit 0

### S1. Reminders fire at the raw start; the guide shift exists because raw EPG times are wrong
- **Where:** §3.11.2 (`:1571`: "Raw times are wall-clock instants, so the guide shift never moves a broadcast"); `ReminderAlert.startAtMs` KDoc (`:929`).
- **Problem.** Protocols research §2.6 says the opposite: "The provider's EPG is often wrong by a whole number of hours. This is the reason for `tvg-shift` … Apply shift to *display and matching only*, never to catch-up request times" (`protocols-m3u-xmltv-xtream.md:199`). The other main use is "+1" channels, which reuse the base EPG with `tvg-shift=1`.
  - The displayed (shifted) time is when the programme airs on that channel. A reminder set on a +1 channel, or on a source the user corrected by +60, fires an hour early.
  - Catch-up correctly stays raw (M1 decision 5).
- **Fix.** Keep `(playlist_id, stable_key, start_min raw)` as the key. Add `reminder.shift_min INTEGER NOT NULL DEFAULT 0` to the v3 auto-migration (one more `ALTER TABLE`). `ReminderBook.set` stores `info.startMin − info.rawStartMin`. `PendingReminder` gains `shiftMin`. The planner fires at `(rawStart + shiftMin) × 60_000 − lead`. Text in F7.
- **WPs:** lead (migration), B, D.

### S2. The reminder card steals SELECT/LEFT/RIGHT/BACK from the grid while the user is browsing
- **Where:** §3.4.1 (`:1296`), §3.4.4 (`:1345`).
- **Problem.** The card appears unprompted. The next OK the user meant for a cell becomes "Switch now", and Left/Right stop moving through programmes for up to 60 s. Back dismisses instead of following the ladder. The same key means two things depending on a card the user may not have noticed.
- **Fix.** Make the card modal: all keys go to it. Ignore input for `REMINDER_CARD_GUARD_MS` (600) after it appears, so an in-flight OK cannot accept. Back = Dismiss. 60 s is short and the decision is binary. This is a lead decision (E20). If pass-through is kept, the card must take **no** keys until the user presses Down twice to focus it.
- **WP:** D.

### S3. Low-end and still thresholds compare MemTotal with nominal RAM sizes
- **Where:** §2.4 `DeviceClass` (`:725-726`), §0.11.
- **Problem.** `totalMemBytes` is the kernel's MemTotal, about 5–10 % below the nominal size.
  - A Fire TV Stick 4K (1.5 GB) reports about 1.4 GiB, which is below `1536 MiB`, so the mini player is a still by default.
  - Product §3.8 meant devices "under 1.5 GB … Fire TV Stick Lite" (`product-review.md:405`).
  - Every 2 GB device (Onn 4K, CCwGTV) reports under 2 GiB and lands in low-end, losing ambient and animation, although product §4.6 says "RAM under 2 GB".
  - The plan's guide-scroll gate is defined "with 1080i50 decoding in the mini window" on exactly these two devices (plan §6, `00-master-plan.md:99`). The default build would not decode video in the mini player on one of them at all.
- **Fix.** `stillByDefault = totalMem < 1.25 GiB` (1 GB class). `lowEnd = isLowRamDevice || totalMem < 1.75 GiB || memoryClass ≤ 192`. This keeps the FTS4K low-end through memoryClass 192 (M2 §3.15) while it still decodes. §5.7: run the scroll benchmark in both Auto and forced High.
- **WPs:** lead, C.

### S4. The merge stamps `name_prefix_ver` although it never rewrites untouched rows
- **Where:** §3.12.3 (`:1623`, "`finish` sets `name_prefix_ver = RULE_VERSION`").
- **Problem.** Unchanged rows go through the touch path, which updates only `sort_index`/missing columns (`RoomMergeSink.kt:119-120`), so their `display_name` stays NULL (after v3) or keeps the old rule's value (after a `RULE_VERSION` bump).
  - If a start-up refresh wins `ImportMutex` before the backfill, the stamp says "done" and the backfill (`:1625`) skips the playlist forever.
- **Fix.** `finish` sets `name_prefix_ver = RULE_VERSION` only if the stored value already equals `RULE_VERSION`. Otherwise it leaves it for `DisplayNames.recompute`. `MergeDisplayNameTest` gains "upgrade: merge before backfill → backfill still runs".
- **WP:** B.

### S5. The Auto prefix rule narrows the decision note and over-strips through group equality
- **Where:** §3.12.1 (`:1584-1615`).
- **Problem.**
  - (a) The note says "an ISO-3166 code or `UK`" (`2026-10-14-settings-and-branding.md:12`). The design restricts this to alpha-2 and lists `USA: CNN` as unchanged. `USA:` is among the most common IPTV prefixes, and `USA` is an ISO-3166 alpha-3 code.
  - (b) Group equality strips `Sky Sports: F1` in group "Sky Sports" to `F1`. The display name is also shown in favourites, recent, search results, the info bar and the reminder card, where the group context is absent.
- **Fix.**
  - (a) Accept ISO-3166 alpha-2 ∪ alpha-3 ∪ {UK} from one fixed list (alpha-3 false positives such as `AND`/`PER`/`MAR` need a following separator, which rarely happens).
  - (b) Group equality applies only when the tag contains no lower-case letter, or is bracketed. `SPORTS: ESPN` and `4K: Planet Earth` still strip; `Sky Sports: F1` does not. Bump `RULE_VERSION` if this lands after any build.
  - Update the table rows to match.
- **WP:** B (E10).

### S6. The ellipsis path is not grapheme- or RTL-safe and lacks the cut width
- **Where:** §0.7, §3.6.4 (`:1458`).
- **Problem.**
  - `breakText` returns a UTF-16 count. Cutting there can split a surrogate pair (emoji in channel names), a ZWJ sequence or a combining mark, leaving tofu before the "…".
  - For RTL titles (Arabic and Hebrew lists are common), `drawText(title, 0, n)` draws the logical start, and the "…" placed after it lands on the wrong side.
  - The renderer also needs the width of `title[0, n)` to place "…", but `TextFit` stores only `n`. That means a `measureText` per overflowing cell per frame.
- **Fix.**
  - `TextFit` stores `(n, cutWidthPx)`.
  - `fit` backs `n` off to a grapheme boundary (`Character.isLowSurrogate`, plus `BreakIterator.getCharacterInstance` once per text off-main for strings that need it).
  - The pager sets a `CELL_RTL` flag bit (`Bidi.requiresBidi` once per `textId` off-main). RTL cells are right-aligned with the ellipsis on the left.
  - Add test rows: emoji at the cut; an Arabic title.
- **WP:** A.

### S7. The `SQLITE_BUSY` detection string does not match the bundled driver
- **Where:** §3.2.3 (`:1252`, "message contains `SQLITE_BUSY` or `(code 5)` **(verify)**").
- **Problem.** `androidx.sqlite` 2.6.2 formats errors as `"Error code: $errorCode, message: $errorMsg"` (`sqlite-jvm-2.6.2-sources.jar`, `androidx/sqlite/SQLite.kt:60-67`). The native step path is presumed to use the same helper format (not readable from the jar). The busy message text is SQLite's "database is locked". Neither proposed pattern matches, so a busy reader falls into the generic error path.
- **Fix.** Parse `Regex("Error code: (\\d+)")` and treat `(code and 0xFF) == 5` as busy (covers extended `SQLITE_BUSY_SNAPSHOT` 517 and `SQLITE_BUSY_RECOVERY` 261). Put it in one `isBusy(e)` helper with a unit test on a constructed message.
- **WP:** B.

### S8. Coil's memory cache is outside the guide memory budget, and nothing is released on `onTrimMemory`
- **Where:** §3.7 (`:1486`), §4.6 (`:1828-1839`).
- **Problem.**
  - On API 25 (Fire TV Stick 4K) logo bitmaps live on the Java heap. Coil's cache at 10 % of `memoryClass` 192 is about 19 MB (5 % low-end, about 9.6 MB). It sits on top of our own 4 MB/2 MB LRU and holds the same bitmaps, so the cap is not enforced.
  - The ≤ 5 MB guide budget and I-15 (run on API 30, where bitmaps are hardware) cannot see it.
  - No section says what happens on `ComponentCallbacks2.onTrimMemory`.
- **Fix.**
  - Logo requests use `memoryCachePolicy(CachePolicy.DISABLED)`; our LRU is the cache, and Coil's disk cache stays.
  - Coil's global memory cache is capped at 2 MB (1 MB low-end) for other images.
  - Add §4.7 "Trim": at `TRIM_MEMORY_RUNNING_LOW` and above, clear `LogoCache`, `TextFit`, the pager width cache, row blocks outside `[top − 6, top + 12)`, the non-current ambient bitmap and the hue cache; at `TRIM_MEMORY_UI_HIDDEN`, all of these plus the snapshot.
  - Add an API 25 memory check to the M6 list.
- **WPs:** A, C.

### S9. `LineupZapList` mixes a captured lineup with the cache's current lineup
- **Where:** §3.16.3 (`:1727`).
- **Problem.** `neighbour` finds `from` in the captured `lineup` and then "loads that row's identity through the rows cache". `GuideRowsCache.channelAt(index)` indexes the cache's **current** lineup (`setLineup`, `:659`). After a refresh re-emits the lineup, or after a source change before `setZapList` lands, the index points at another channel and CH+ tunes the wrong one.
  - The cache is also "Main-thread API" (`:657`). `neighbour` is a suspend function the controller may call from its own scope.
  - `neighbour` takes a `ChannelIdentity`, while the lineup holds row ids. Mapping one to the other needs `GuideLibrary.rowIdOf`.
- **Fix.** `LineupZapList` holds the captured `rowIds`. It resolves `from` with `library.rowIdOf` and a scan, steps with wrap, and loads the target with `library.channels(rowIds, i, i + 1)`, never touching the cache. Skip ids that come back null (purged). The controller's `ZapList` KDoc already says the list skips hidden/locked (`PlaybackApi.kt:75-76`); the lineup predicate does that.
- **WP:** C.

### S10. Stale snapshots and overlapping cells
- **Where:** §3.4.9 `Window` (`:1386`), §3.2.4 rule 2 (`:1261`).
- **Problem.**
  - (a) A snapshot built for the previous lineup can arrive after the `Lineup` event; `invalidate()` does not cancel one in flight. It then draws channel X's programmes on channel Y's row for a frame and drives `RESOLVE` against the wrong cells.
  - (b) Rule 2 says overlaps are "resolved by clipping the later programme's start", then "emit `p` (its true start/end)". Those contradict each other, and a programme wholly inside its predecessor yields `start ≥ end`. Both break the non-overlapping invariant the navigator relies on (`:300`).
- **Fix.**
  - (a) The state machine ignores `Window` events whose `lineupVersion` or `rows.version` basis is older than the current one; the pager re-queries.
  - (b) Rule 2 text: "emit `p` with `start = max(p.start, c)` (its true end; a true start before `fromMin` is kept only for the first cell); skip `p` when `p.end ≤ c`." Add a `RowCellsBuilderTest` row for a nested programme.
- **WPs:** D, A.

### S11. Five-key reachability: the specified test cannot pass, and long-press is not universal
- **Where:** §3.4.8 (`:1376-1378`).
- **Problem.**
  - `FiveKeyReachabilityTest` must reach "every … overlay". `Overlay.Number` is reachable only through DIGIT or NUMBER_ENTRY, and NUMBER_ENTRY has no default binding (`:1542`, `:1746-1766`). `Overlay.Exit(dialog = true)` exists only under `ExitConfirmation.ASK`. The test either fails or gets weakened ad hoc.
  - Separately, add-favourite, hide and group actions are reachable **only** through CONTEXT_MENU (long OK). Many HDMI-CEC remotes deliver DOWN/UP pairs with no hold, and have no MENU.
- **Fix.**
  - State the exclusions in §3.4.8: Number overlay = accelerator only (product §2.1); Exit dialog under ASK. Run the reachability search once per `ExitConfirmation`.
  - Add a lead decision (E21) on a "More…" panel button that opens the same Menu. It costs one `PanelButton`, and makes every function reachable without long-press.
- **WP:** D.

### S12. `TimeLabels` must be allocation-free and safe across two threads
- **Where:** §2.2 `TimeLabels` (`:459-474`), §2.11.
- **Problem.**
  - `minuteOfDay`/`dayOf` through `ZoneId.rules.getOffset(Instant)` allocate an `Instant` per call. The renderer calls them per tick label per frame, and `RendererAllocationTest` will fail without saying why.
  - The pager uses the same `TimeLabels` on `box-guide-epg` (time texts, `:1248`) while `reset` runs on main. That is a data race on the tables.
- **Fix.** The §2.2 KDoc adds:
  - "Offsets come from a precomputed transition table (±16 days around now, rebuilt by `reset` and at local midnight); `minuteOfDay` is integer arithmetic."
  - "`reset` builds new immutable tables and publishes them through one `@Volatile` reference; readers on any thread take a consistent snapshot."
- **WP:** A.

### S13. Toast and failure contracts cannot express the design's own messages
- **Where:** §2.5 `GuideToast` (`:864`), §2.8 `ArchiveTargets` (`:1001-1002`), §3.8.3 item 4, §3.11.3, §3.13.6 step 5.
- **Problem.**
  - `GuideToast` has no value for "No favourites yet. Hold OK on a channel to add it." (`:1505`), "{Channel} is no longer in your playlist." (`:1576`), or the three catch-up failure texts (`:1675`).
  - `catchupTarget` returns null for both "channel gone" and "credentials missing", so step 1 cannot pick between `CHANNEL_GONE` and `CREDENTIALS` (`:1671`), and the toast cannot name the right next step (CLAUDE.md error rule).
- **Fix.** F8 adds `NO_FAVOURITES`, `REMINDER_CHANNEL_GONE`, `CATCHUP_FAILED` (with `arg` = `CatchupFailure.name`), and an `ArchiveTargets.catchupTarget` result type.
- **WPs:** lead, C, D.

### S14. Tests do not measure the zap gate, and the frame metric may not exist on the API 30 emulator
- **Where:** §5.7 (`:1912-1915`), §5.9 (`:1921-1933`).
- **Problem.**
  - Plan §6's zap gate (P50 ≤ 1.2 s, P90 ≤ 2.0 s, no black frame) now runs through a new path: OK in the guide, then `LineupZapList`, then the controller, with the grid drawing. No M3 test logs it. Journey (c) "20× CH+" pages the grid in GUIDE; it does not zap.
  - **(verify)** `FrameTimingMetric` reports `frameOverrunMs` only on API 31+. On `TheBox_TV_API30` only `frameDurationCpuMs` exists, which excludes GPU time.
  - `BaselineProfileMode.Require` fails if the profile is not yet in the APK.
  - Macrobenchmark on an emulator needs `androidx.benchmark.suppressErrors=EMULATOR`.
- **Fix.**
  - Add a journey (d): FULL, 20× CH+ at 1 Hz, reading `controller.diagnostics.zaps()` P50/P90 (logged, not asserted).
  - Add I-17: OK on 10 rows in the guide, then zap records logged.
  - Run `Partial(BaselineProfileMode.UseIfAvailable)` until the profile is committed.
  - State which metric names are logged on API 30.
- **WP:** D.

### S15. WP-C is the critical path and the largest package
- **Where:** §1.4 (`:95-119`).
- **Problem.** WP-C has about 30 files: root, hole, ambient (4), screen, 7 overlays, VM/effects/mapper, Search screen + VM, full screen, error text + strings, MainActivity, ImageModule, and the branding toolchain. The toolchain depends on tools that are not installed (`:2066`). WP-C also merges last, and I-1…I-16 cannot start until it lands.
- **Fix.**
  - Move branding (`tools/branding/`, `res/drawable*`, `mipmap*`, manifest icon) to a WP-E on Sonnet under §3.17. It is disjoint by file.
  - Move `SearchScreen`/`SearchViewModel` to WP-B, which owns `GuideSearch` and the ranking.
  - WP-C keeps the root, hole, ambient, guide screen, overlays and VM.
  - Merge order becomes A, B, D, E, C.
- **Lead decision:** E23.

### S16. Hold behaviour is ambiguous for the panel and too tight for prefetch
- **Where:** §3.4.7 (`:1372`), §3.9.1 (`:1516`), §3.2.3 margins (`:1245`).
- **Problem.**
  - "The panel freezes (no `loadInfo`)" leaves open whether `PanelState` (title, time line) is rebuilt per repeat. B2.8 says freeze the panel; relaying out the 64 px title at 20 Hz is the jank B2.8 names.
  - Vertical prefetch margin: at 20 Hz OS repeat, 2 margin rows give 100 ms of lead. A window query of 30–50 ms on a low-end device plus a main-thread hop leaves the bottom row as a skeleton during most holds.
- **Fix.**
  - §3.4.7: "While `repeat > 0`, `PanelState` is not rebuilt; it is rebuilt on `Release` and `loadInfo` follows 150 ms later."
  - §3.2.3: "While a vertical hold is active, the target is biased in the direction of motion (rows `[top − 2, top + 24)` going down, mirrored going up) and the margin is 6 rows."
- **WPs:** D, B.

---

## C. Nice to have

- **N1. DST day jump.** `localMidnight(d) + minuteOfDay(anchor)` (`:1401`) is off by 60 min after a transition on that day. Use local date-time arithmetic in `GuideLimits`.
- **N2. Recent reorders under the user.** Recently watched re-sorts about 5 s after every tune (`recordWatched` → `channel_prefs` → tracker), so the focused row jumps. Freeze the Recent order while it is the displayed source.
- **N3. Per-frame lookups.** `GuideRowsCache.channel(i)` must use primitive block keys (`MutableIntObjectMap`). Block indices above 127 box per call. `LogoCache.get` must dedupe in-flight loads, or it launches a coroutine per frame per missing logo.
- **N4. Host bounds.** Size `GuideGridHost` to the panel rect and translate the renderer. A full-window `Spacer` gives TalkBack a full-screen focus rectangle.
- **N5. Missed reminders and re-setting.** On start, show "Missed while The Box was closed: {n} reminders" instead of a silent expiry. Note that `index_reminder_identity` is unique regardless of state (`ReminderEntity.kt:14`, insert IGNORE `ReminderDao.kt:13`), so re-setting a dismissed or fired reminder returns false. `set` should delete non-pending rows for the key first.
- **N6. Still to FULL.** Re-enabling video waits for a keyframe (up to the GOP length). Keep the FULL scrim until the next first-media milestone when coming from HIDDEN, not "two frames".
- **N7. Disk writes on main.** `KeyCapabilities.record` should write only on a newly seen code. Read `GuideSessionStore`/`keys_seen` off-main at VM creation (StrictMode disk read).
- **N8. Panel focus index.** Clamp `panelFocused` when `PanelState.buttons` changes under it (a programme ends: [WATCHING, FROM_START] → [WATCH]).
- **N9. Corner cross-fade.** `HoleChrome`'s corner fill must apply the same cross-fade alpha as `AmbientBackground`, or the corners pop for 1.2 s on each tune.
- **N10. Golden image.** API notes §7 (`:226`) describe a dependency-free golden: render `GuideRenderer` into a `Bitmap` under Robolectric `@GraphicsMode(NATIVE)` with the bundled fonts. It is deterministic per Robolectric version; the emulator is not (host GPU). One reference frame catches visual regressions the op recorder cannot.
- **N11. Sync chip.** "Guide update failed · Hold OK for details" names an action that does not exist in M3: the chip is not focusable and there is no details screen. Use "Guide update failed. Details arrive with Settings" until M5, or drop the clause.
- **N12. Groups panel shortcuts.** Add Search and Settings rows at the top of the Groups panel, as TiviMate's left menu has. With the E4 rule, every top-level function is then two or three presses from any row (today Search needs Up to row 0 of a 2,000-row lineup).
- **N13. Parental on startup.** `GuideLibrary.lastWatched()` must apply `PARENTAL`, or the startup tune can play a locked channel. Call `setZapList` when the first lineup arrives after the startup tune.
- **N14. Archive band.** Skip the band when `now < scrollMin`; the rect is negative. Find a row's first visible cell by binary search on `cellStart`.
- **N15. Recompute pass.** Chunk `DisplayNames.recompute` by `id` ranges (`WHERE id > ? ORDER BY id LIMIT 2000`) rather than one long-lived cursor. 200k channels take about 20 s under `ImportMutex`; run it after the first frame.
- **N16. KDoc.** The `ReminderAlerts` KDoc says "(WP-D, :data)" (`:949`); the implementation is in `:app` (`:132`).
- **N17. Catch-up bottom panel.** The mini bottom panel shows `at(channel, now)` while `isArchive`, which is the live programme, not the archived one. Use the archive programme's `ProgrammeInfo` when `isArchive`.
- **N18. Search past hits.** Decide "ended" by display end (raw end + shift). A CONTINUATION-split programme over 6 h shows its first segment's end, so a 7 h film in its 7th hour reads as finished. Join the continuation's end, or skip ended-but-continued rows (flag `CONTINUED`).

---

## D. Verified correct

1. **Timeline width.** 1558 px. Prototype: grid `left:64px;right:64px` = 1792, `border:1px`, `padding:0 16px 12px; box-sizing:border-box`, label `width:200px;flex:none`, row `display:flex` with no gap (`TV Guide.dc.html`). That gives 1792 − 2 − 32 − 200 = 1558 and 8.6556 px/min. B2.1's 1560 omits the border; the design reference's 1656/1456 is wrong. Commit 0 item 7 is right.
2. **Mini player.** `left:64px;top:56px;width:720px;height:405px;border-radius:20px`; shadow `0 30px 80px rgba(0,0,0,.55)` + ring `.14`. Header `left:832px;top:56px;right:64px;height:405px`. Matches `GuideGeometry` (`:183-185`).
3. **Now line.** `top:-6px`, 2 px, glow 12; the dot sits at rows top − 5. Matches §3.1.
4. **Nothing guide-drawn behind the hole.** The hole ends at x 784/y 461; the header starts at 832 and the panel at 500 (§4.5). Overlay rects in §3.15 all avoid the hole; the Menu/Sheet floor at y 470 is below 461.
5. **Hole rect.** Computed from `GuideMetrics` (pure maths, `:392-393`), not from `onGloballyPositioned`, so there is no layout → state → layout loop (API notes `:162` warn about it).
6. **Root tree.** Order `Ambient → VideoLayer → HoleChrome → screen` matches B1.2 and M2 §3.11. `HoleChrome` is a later sibling, not an ancestor, and draws inside `videoRect` only at its edges (corner masks, 1 px ring), plus the intentional still and scrim.
7. **Viewport HIDDEN.** It disables the video track, keeps audio and the connection, and leaves the surface at 1×1 (M2 `m2-playback-core.md:1903`), so "Still keeps audio, no decode" is accurate.
8. **AFR is not triggered from MINI.** The design sets FULL only in FULL mode. M2 gates non-seamless switches on `viewport == FULL` + 2 s dwell (`m2-playback-core.md:1828`).
9. **HDR poster.** It comes from `VideoInfo.posterMode` (`PlaybackApi.kt:120-121`), set by M2 only in MINI.
10. **Catch-up computes no time of its own.** It passes raw instants and device now in `ArchiveWindow` (`CatchupContracts.kt:87-95`). The builder applies the panel zone, `catchup-correction` and skew through `TimeBase` (`PlaylistRepository.kt:159-165`). This matches the CLAUDE.md rule and M1 decision 5.
11. **`tuneArchive`.** `ArchiveTune(identity, request, startOffsetMs)` exists (`PlaybackApi.kt:37, 80`). `StreamUrlResolver.catchupTarget` is internal to `:player` (M2 §3.14), so a public seam is needed.
12. **25 error codes.** `PlaybackErrorCode` has 25 codes with `defaultSteps` (`PlaybackContracts.kt:126-152`), and `ErrorText` completeness is testable.
13. **Long-press OK.** Detection follows API notes §2.2 exactly (`:86-89`): `repeatCount == 1` or the 500 ms timer, whichever comes first; UP swallowed after a long press; unmatched UPs ignored.
14. **Single focusable node.** `onPreviewKeyEvent` + `focusRequester` + `focusable`, with `requestFocus()` from a `LaunchedEffect` after attach (API notes `:80`, `:97-98`). Correct, once placed per B1.
15. **Grid draw path.** `drawBehind` + `drawIntoCanvas { it.nativeCanvas }` exist as cited (`DrawModifier.kt:93`, `AndroidCanvas.android.kt:53`). Reading `frameVersion`/`Animatable` values only inside `drawBehind` invalidates draw, not composition (API notes `:171`).
16. **Text pipeline.** No mixing: plain `Paint` for the grid; Compose `Text` only in the panel. The `TextMeasurer` rejection reasons match API notes `:56-62` (8-entry LRU, per-call allocation, the `Size.Unspecified` constraint trap).
17. **Coil API.** `ImageRequest.size/scale/precision`, `ImageLoader.execute`, `Image.toBitmap` and `allowHardware` are as cited (API notes §5). Hardware bitmaps can be drawn on a hardware canvas (`:192`).
18. **Search amendment SQL.** Binding `?2 = pastFromMin − 360`, `?3 = pastFromMin` keeps `programme_by_text` usable (`FtsSupport.kt:52-55`). `CONTINUATION = 1 shl 5 = 32`, so `(flags & 32) = 0` is the right filter. The ordering problem is B6.
19. **Generation lifecycle.** Swap order (open new, re-resolve, publish, close old on the same thread) matches M1 §2.5.3. `GenerationHandle.release` deletes on the closing thread (`m1-data-layer.md:2333`). The search reader uses the handle's separate `aux` connection (`EpgReaderImpl.kt:126, 145`), so it never contends with the guide connection.
20. **User data keys.** Reminders are keyed `(playlist_id, stable_key, raw start_min)`, unique (`ReminderEntity.kt:9-17`). Favourites `(list, playlist, stable_key)`, prefs `(playlist_id, stable_key)`, group prefs `(playlist_id, kind, group_key)`, and `GuideSource.Group` persists `groupKey`, not `channel_group.id` (`:608-614`). No EPG row id is held across a swap except `cellTextId` (B4).
21. **Parental filtering in SQL.** Applied for lineups, groups, number entry and channel search (`ChannelDao.kt:232-236`, `GroupDao.kt:29-31`, `ChannelDao.kt:130-144`). The design extends it to programme-search candidates (`:1554`). This meets architecture C7.
22. **Indexes.** `epg_match`/`epg_mapping` in v2 have only `source_id` indexes (2.json parsed: `index_epg_match_source`, `index_epg_mapping_source`), so the new `(source_id, xmltv_id)` indexes are useful.
23. **v3 migration.** Additive and expressible as `@AutoMigration(2, 3)`: `ADD COLUMN` with `defaultValue` for the NOT NULL columns, plus `CREATE INDEX`. No table rebuild. `LibraryDatabase` already uses an auto-migration 1→2 (`LibraryDatabase.kt:52-55`).
24. **`display_name` causes no churn.** It derives from `name` + `groupTitle`, both already in the content hash (`m1-data-layer.md:2435`), so a merge never sees a change it would not have seen anyway. The recompute pass writes only `display_name`. Stable keys, matcher input and `channel_fts` keep the raw name. `COALESCE(custom_name, display_name, name)` with NULL-when-unchanged is sound.
25. **`channel.display_name`.** `ChannelRow` uses `@Embedded ChannelEntity` with `SELECT c.*` (`ChannelDao.kt:11-20, 222-229`), so the new column flows through without query edits; only the Kotlin name choice changes.
26. **Hash64 calls.** `Hash64.of(null)`, `Hash64.long`, `Hash64.str` exist with the used signatures (`Hash64.kt:13-32`).
27. **Reminder lead.** `ReminderEntity.lead_sec` defaults to 60, and the card at T − 60 s matches product §3.6.
28. **Prototype labels.** `watchLabel`/`durLabel` and the "start + 1 min" anchor are quoted correctly from the script. Plan §4 replaces the anchor rule.
29. **Units.** Every size is in design px through `GuideGeometry` × `scale` (`BoxDesignScale.kt:63-66`); no dp/sp appear in the grid path.
30. **No telemetry.** Nothing added sends data. Logs go through `BoxLog` with no URLs (§6.3).

---

## E. Lead decisions needed

Design §7 items:

1. **Virtual focus for the whole guide.** Accept, with the B1 key-sink placement. It makes every key table a JVM test and avoids tv-material's scale/clip layer (API notes `:104`). The cost (hand-rolled announcements) is small next to non-deterministic focus routing.
2. **Real ellipsis over clip-and-fade.** Accept, with S6 (grapheme/RTL) and B4 (cache keys). The fade needs an opaque background colour, which translucent cells over the ambient and the archive band do not have.
3. **Logo replaces the name in the 200 px column.** Accept. After padding, number and gap, about 130 px remain; a 96 px logo leaves about 20 px, so name + logo cannot both fit. The panel always shows the name, and the "Channel logos in guide" toggle (product §4.6) is the escape.
4. **Left at the edge.** Change the rule. Left opens Groups when there is **no earlier cell the user can watch or is airing**:
   - on rows without usable archive (`archiveDays == 0` or suspect), that is Left from the cell containing `now`;
   - on archive rows, it stays the archive limit;
   - hold-Left time steps, RW and the day strip still reach the non-watchable past.
   
   Reason: this gives TiviMate's one-press gesture on the common row, and catch-up browsing is untouched where it matters. The `GridMove.LEFT` → `LeftEdge` test changes in one place.
5. **Foreground-only reminders, no notification.** Accept. Plan §2 commits exactly this; Fire OS hides third-party notifications and exact alarms are denied by default (API notes §6). Fix the fire time (S1), add the missed-reminder toast (N5), and revisit `AlarmManager` as a best-effort extra in M5.
6. **Hue not persisted.** Accept. One 16 × 16 decode per tune off the main thread, from Coil's disk cache, is cheaper than a migration and a write path. Revisit only if D1/D2 show it on the zap path.
7. **Minimal catch-up in M3 via `ArchiveTargets`.** Accept, with the result type of S13. Without it, OK on an archive cell (product S7) does nothing until M4, and the seam is additive.
8. **Page = 6 rows.** Accept. Product §2.1 and S7 say 6, and the grid shows 6 rows (row 5 ends at 949, inside the panel). B2.1's 5 came from 5.9 visible rows.
9. **Still keeps audio and the connection.** Accept, with S3 thresholds and N6. The low-RAM cost is the video decoder and GPU composition, not a TS socket and an audio decoder. Stopping would make the mini player useless as a radio and cost a full zap on OK.
10. **Prefix details.** Colon without a space: accept. Hyphen needs spaces: accept. Tuvalu `TV:`: accept as a known false positive. Fixed list: accept. Change two things (S5): alpha-3 codes count, as the note's "ISO-3166 code" says, and group equality only for tags with no lower-case letters or in brackets. `Sky Sports: F1` → `F1` reads badly in favourites and search.
11. **ON NOW secondary = From start.** Accept. Product §3.7 says so, and Record is v2.
12. **Adult and locked groups invisible in M3.** Accept, plus a debug-only `?unlocked=1` deep-link switch for testers. Record for M5: with auto-lock on and **no PIN set**, the predicate (`ChannelDao.kt:235`) hides adult groups forever. S6 onboarding must ask the user to set a PIN or turn auto-lock off; "no PIN" must not mean "unreachable".
13. **Search looks back 7 days, title matches first.** Accept, but only with the B6 split query. As specified, the look-back returns week-old airings first.
14. **`:ui` gets Hilt.** Accept. `@HiltViewModel` needs it. `:ui` still never sees `:data`, provided B7's feeds land.
15. **Timeline 1558.** Accept; verified (D1).
16. **Earliest minute from an upper bound of archive days.** Accept. It is one aggregate query; over-scrolling by at most a day renders honestly as "past, not in archive".

Added by this review:

17. **Back ladder after leaving FULL** (B2). Recommend the product nav map's reading: after Back from FULL, the next Back at home exits (hint). Product S7 and §3.9 read as an endless GUIDE ↔ FULL loop otherwise.
18. **Raw versus display time for reminders** (S1). Recommend display time for firing, raw for keys and catch-up, as protocols research §2.6 says. Confirm that M1 decision 5 (catch-up raw) still holds for "+1" channels; if a beta provider shows otherwise, it is a per-playlist setting, not a guide change.
19. **Low-end and still thresholds** (S3). Recommend still < 1.25 GiB and low-end < 1.75 GiB or `memoryClass ≤ 192`. Otherwise the plan's reference devices run a different configuration from the one the gate describes.
20. **Reminder card modal or pass-through** (S2). Recommend modal with a 600 ms input guard: a 60 s binary card should not change what OK means on the grid behind it.
21. **"More…" panel button** (S11). Recommend adding it. CEC remotes without hold or Menu otherwise cannot favourite or hide, and the five-key rule assumes long-press works everywhere.
22. **Search and Settings rows in the Groups panel** (N12). Recommend. At row 1,500, Search currently needs 1,500 Up presses with five keys.
23. **Split WP-C** (S15). Recommend: branding to a WP-E on Sonnet, Search to WP-B. WP-C is the critical path and merges last.

---

## F. Contract text to change

Each block replaces the cited text verbatim. Kotlin is normative as in §2.

### F1. §3.2.3 "Snapshot" bullet (`m3-guide.md:1249`)

> - **Snapshot** arrays are allocated fresh for every snapshot (no recycling: worst case ≈ 2,000 cells ≈ 200 KB, off-main) and never written after `snapshots.value = data`. The state machine drops a `Window` whose `lineupVersion` differs from the current lineup's or whose rows were built from an older `rows.version` than the last `RowsChanged` it processed for those rows; the pager re-queries on the next `ensure`.

### F2. §2.7 `EpgReader` amendment (replaces `:981-992`)

```kotlin
/**
 * FTS over title+subtitle; airings ending after [nowMin] (raw), best text first, at most [perText] airings per
 * text in start order. Rows flagged ProgrammeFlags.CONTINUATION are never returned (M1 code review Later 10).
 */
fun search(query: String, nowMin: Int, limit: Int = 50, perText: Int = Int.MAX_VALUE): List<EpgSearchHit>

/**
 * Past airings for catch-up search: end_min in (pastFromMin, nowMin] (raw), best text first, at most [perText]
 * airings per text, newest first. CONTINUATION rows are never returned. Default body returns emptyList() so
 * fakes compile; EpgReaderImpl overrides it.
 */
fun searchPast(query: String, nowMin: Int, pastFromMin: Int, limit: Int = 100, perText: Int = 5): List<EpgSearchHit> = emptyList()
```

`FtsSupport.AIRINGS_SQL` gains `AND (p.flags & 32) = 0` and is unchanged otherwise. A new `PAST_AIRINGS_SQL`:

```sql
SELECT … WHERE p.text_id = ?1 AND p.start_min >= ?2 AND p.end_min > ?2 + 360 AND p.end_min <= ?3 AND (p.flags & 32) = 0
ORDER BY p.start_min DESC LIMIT ?4
```

It binds `?2 = pastFromMin − 360`, `?3 = nowMin`. The FTS4 forms get the same flag filter and a `p.end_min <= ?` variant. §3.10.2 step 1 becomes: "`reader.search(q, now, limit = 150, perText = 8)` and `reader.searchPast(q, now, now − min(7, D) × 1440, limit = 100, perText = 5)`, merged."

### F3. §2.4 `GuideLibrary` and `GuideRowsCache` additions

```kotlin
interface GuideLibrary {
    // … existing members …
    /**
     * Ticks (debounced 500 ms) whenever a row's content may have changed without the lineup's membership
     * changing: tables channel, channel_prefs, playlist, epg_mapping, epg_match, epg_source.
     */
    val rowContent: Flow<Long>
}

interface GuideRowsCache : GuideRows {
    // … existing members …
    /** Reload every loaded block in place (keeps the lineup and indices); bumps [version] and emits [changes]. */
    fun reloadLoaded()
}
```

§3.2.1 adds: "The VM collects `library.rowContent` and calls `rowsCache.reloadLoaded()`. The pager re-queries rows whose `(epg, epgShiftMin)` differ from the snapshot's basis."

### F4. New `core/.../guide/GuideEnvironment.kt` (commit 0), plus `GuideEvent` and `NamePrefixRule` edits

```kotlin
package app.thebox.core.guide

import kotlinx.coroutines.flow.StateFlow

/** EPG retention the guide limits use (DataSettings in :data; bound in GuideDataModule). */
data class GuideDataWindow(val pastDays: Int = 2, val futureDays: Int = 7)
interface GuideDataWindowSource { val window: StateFlow<GuideDataWindow> }

/** Sync chip input (SyncStatusRepository in :data maps to this; bound in GuideDataModule). */
data class SyncFeedState(val busy: Boolean, val progress: String?, val unacknowledgedFailures: Int)
interface SyncFeed { val state: StateFlow<SyncFeedState> }

/** Bound in :ui (InputModule, WP-D) from ActivityManager; computed once. */
fun interface DeviceClassSource { fun get(): DeviceClass }

/** Debug-only probe for the instrumented suite: the live VM registers its machine; release builds bind a no-op. */
interface GuideDebugProbe {
    fun register(machine: GuideStateMachine?)
    val current: GuideStateMachine?
}
```

In `GuideEvent`, replace `FocusChannel` with:

```kotlin
/** SEARCH closed. [channel] non-null: focus it in the grid; [tuned]: it was tuned from search, go FULL. */
data class SearchClosed(val channel: GuideChannel?, val tuned: Boolean) : GuideEvent
data class DataWindow(val window: GuideDataWindow) : GuideEvent
```

`GuideStateMachineFactory.create` is unchanged. `GuideLimits` reads the last `DataWindow`.

In §2.9.2, `NamePrefixRule.companion.parse` is removed from the contract. WP-B adds `object NamePrefixRules { fun parse(mode: String, customJson: String?): NamePrefixRule }` in `ChannelNamePrefixImpl.kt`.

### F5. §3.18.1 last paragraph and §3.6.5 host sentence

§3.18.1, replacing "`KeyRouter` (`:ui`) turns … system handles them).":

> `KeyRouter` (`:ui`, WP-D) turns a Compose `KeyEvent.nativeKeyEvent` into `KeyInput`, records the key in `KeyCapabilities` (only when first seen), runs the detector with `hasLong = keyMap.long(context, code) != null` (`context` = PLAYER in FULL, else GUIDE), maps gestures to `BoxAction`s through `KeyMap`, and dispatches `GuideEvent.Act(action, repeat, digit, heldMs)`. `ReleaseAfterRepeat` → `Release`. WP-D ships `Modifier.boxKeySink(router: KeyRouter, requester: FocusRequester)` = `focusRequester(requester).onPreviewKeyEvent(router::onKey).focusable()`. WP-C applies it **once**, on a `Box(Modifier.fillMaxSize())` that is a sibling after `HoleChrome` and wraps the `when (ui.mode)` screens, so it survives every mode change. It adds no layer, alpha or clip, and is not an ancestor of `VideoLayer`. The router returns `true` for every key it handled, including the matching KeyUp. In `ScreenMode.SEARCH` it returns `false` for every key except BACK while the search field is empty and the IME is hidden. `requester.requestFocus()` runs from `LaunchedEffect(ui.mode)`, on `Lifecycle.Event.ON_RESUME`, and after `SearchClosed`. `detector.reset()` runs when the sink loses focus.

§3.6.5, replacing "The node also carries `focusRequester`, `focusable()` and `onPreviewKeyEvent` (§3.18.1); … API notes §2.1).":

> The node carries no focus or key modifiers (keys arrive through the root key sink, §3.18.1).

### F6. §3.6.5 layer sentence (replaces "No `graphicsLayer` on it or any ancestor (the root rule, §3.13.1).")

> The node carries `Modifier.graphicsLayer()` with default parameters (no alpha, clip, shape or render effect): a plain RenderNode, so a grid redraw re-records only the grid. `GuideScreen`'s header/panel column and `AmbientBackground` carry one too. None of them is an ancestor of `VideoLayer`. The root rule (§3.13.1) forbids layers only on ancestors of `VideoLayer`.

### F7. §2.6 reminders

```kotlin
data class PendingReminder(
    val id: Long,
    val identity: ChannelIdentity,
    /** Key (raw, unshifted). */
    val rawStartMin: Int,
    val rawEndMin: Int,
    /** Guide shift at set time (reminder.shift_min); the programme airs at rawStart + shiftMin on this channel. */
    val shiftMin: Int,
    val title: String,
    val channelName: String,
    val leadSec: Int,
)
```

In `ReminderAlert`:

```kotlin
/** Displayed (shifted) start, ms: (rawStart + shiftMin) × 60_000. */
val startAtMs: Long,
```

§2.9.1 table adds a `reminder` row: `shift_min`, `INTEGER NOT NULL`, default `0`, "guide shift when the reminder was set". The generated migration adds:

```sql
ALTER TABLE `reminder` ADD COLUMN `shift_min` INTEGER NOT NULL DEFAULT 0;
```

§3.11.2 becomes: "`startMs = (rawStart + shiftMin) × 60_000`, `endMs = (rawEnd + shiftMin) × 60_000` … The key stays raw, so a later shift change does not orphan the reminder."

### F8. §2.5 `GuideToast` and §2.8 `ArchiveTargets`

```kotlin
enum class GuideToast {
    REMINDER_SET_FIRST_TIME, REMINDER_SET, REMINDER_CANCELLED, REMINDER_CHANNEL_GONE,
    ADDED_FAVOURITE, REMOVED_FAVOURITE, NO_FAVOURITES, CHANNEL_HIDDEN, NO_CATCHUP,
    /** arg = CatchupFailure.name; ErrorText maps it to reason + next step. */
    CATCHUP_FAILED,
    EXIT_HINT,
}
```

```kotlin
sealed interface CatchupTargetResult {
    data class Found(val target: app.thebox.core.catchup.CatchupTarget) : CatchupTargetResult
    data object ChannelGone : CatchupTargetResult
    data object CredentialsUnavailable : CatchupTargetResult
}
interface ArchiveTargets {
    /** Main-safe. */
    suspend fun catchupTarget(identity: app.thebox.core.refresh.ChannelIdentity): CatchupTargetResult
}
```

### F9. §2.4 `DeviceClass`

```kotlin
data class DeviceClass(val lowRam: Boolean, val totalMemBytes: Long, val memoryClassMb: Int) {
    /** MemTotal runs ~5–10 % under nominal RAM: 1.75 GiB catches nominal 1.5 GB devices, not 2 GB ones. */
    val lowEnd: Boolean get() = lowRam || totalMemBytes < 1792L * 1024 * 1024 || memoryClassMb <= 192
    /** Nominal 1 GB class only (product §3.8: Fire TV Stick Lite). */
    val stillByDefault: Boolean get() = totalMemBytes < 1280L * 1024 * 1024
}
```

### F10. §3.4.6 Back ladder, step 4, and §3.4.5 BACK

> 4. Something is tuned (`phase ∉ {IDLE, STOPPED}`) **and `returnedFromFull` is false** → FULL.

> | BACK | GUIDE, viewport MINI, focus home (tuned row at now), `returnedFromFull = true` (cleared by any other action); product nav map: the next Back shows the exit step |

---

## What I could not verify

- **Compose layer scope (B8).** That a draw invalidation without a layer re-records up to the root comes from my knowledge of Compose internals. I did not re-read it in the 1.12.1 sources. WP-A confirms it with a trace.
- **Native busy message (S7).** The JNI error path of `sqlite-bundled` 2.6.2 is native code. Only the Kotlin `throwSQLiteException` format was read.
- **`FrameTimingMetric` on API 30 (S14).** Whether `frameOverrunMs` is API 31+ only is from memory.
- **`InvalidationTracker.createFlow` in Room 2.8.5 and `LineBreak.Heading`.** Both are still (verify), as the design says.
- **SurfaceView in Compose.** Whether Compose's `AndroidView` wrapper adds its own layer around the `SurfaceView` is not checked. The hole works in M2's harness; I-2/I-3 and D1 on hardware cover it.
- **MemTotal figures (S3).** The figures for Fire TV Stick 4K and Onn 4K are typical values, not measured on the devices.
