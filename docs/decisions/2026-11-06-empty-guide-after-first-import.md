# Empty guide after the first playlist import

Date: 2026-11-06. Integration-pass finding (2026-11-05, API 30 TV emulator, fresh install); diagnosed and fixed on main.

## Finding

After the very first playlist import through onboarding (Welcome → Xtream login → Importing your playlist → Start
watching) the guide showed the ruler and an empty grid: no rows, no source name on the group chip, no empty-state
message. After an app restart the same data showed channels and programmes.

## Reproduction

Reproduced on the emulator (fake panel, `adb uninstall` + install of the debug APK, onboarding by D-pad). Both
channels and programmes were missing: the guide had no lineup at all, not an empty EPG. Restarting the app showed
All channels with 13 rows and programmes. Deterministic, not a race; not emulator-specific.

## Cause

`GuideStateMachineImpl.onSources` picked the startup source (§3.8.4 `initialSource`: last source / favourites / All
channels) only on the **first** `GuideEvent.Sources`. The guide's view model is created at app start, behind the
Welcome screen, so on a fresh install its first Sources is the empty list (no playlist yet). `initialSource` returns
null, nothing is loaded, and `sourcesKnown` is set. When the import adds the playlist, `GuideLibraryImpl.sources`
re-queries and emits All channels plus the groups (Room invalidation works as designed), but that Sources was no
longer the first, so no source was ever applied and `effects.loadLineup` never ran. The empty state also cleared
(`NO_PLAYLIST` needs no ALL entry), which is why the grid was blank with no message. A restart starts with the playlist
in the first Sources.

Ruled out: the epg.db generation swap (the pager's generation flow and `GuideEvent.Generation` are live), Room
invalidation across the hand-off (the Sources update did arrive), `booting` (the guide was drawn), a cached empty
lineup (no lineup was ever requested).

## Fix

`onSources` applies the startup source whenever there is still no lineup and no requested source, not only on the
first Sources (`persist = false` as before). Once a source is requested the condition is false, so later Sources
(counts changing during the import) never reload or move it.

Regression test: `GuideBehaviourTest.StartupAndData.firstPlaylistAfterAnEmptyStartLoadsTheStartupSource` (empty
Sources, then the playlist's Sources → `loadLineup(All(1))`, focus on row 0, no empty state; fails before the fix).

## Follow-ups (same day)

**Removed playlist keeps the old source.** Removing every playlist and then adding a different one kept the requested
source `All(<old id>)`, so the guide stayed empty. `onSources` now also checks whether the current All/Group source's
playlist still has an All channels entry; when it has none (deleted or turned off) and something else is listed, it
applies the startup source of the new list and persists it. With nothing listed it stays put (the empty state shows).
Test: `GuideBehaviourTest.StartupAndData.aRemovedPlaylistMovesTheGuideToTheNewOne`.

**Import screen "0 of 13 matched".** The S5 fold treated the guide as done after 1.5 s (3 ticks) without guide work in
WorkManager. The refresh enqueues the EPG import before writing its log row, but `enqueueUniqueWork` is asynchronous and
the work-info flow can surface it later than that, so on the emulator the counts were read before the guide build had
matched anything (the build then matched 13 of 13). `RefreshReport.guideWorkQueued` (additive, default false) now records
that the refresh queued an import or patch; the fold then waits until that work has been seen running or an
EPG_BUILD/EPG_PATCH log row started after the import began, with a 60 s fallback. Reports without the flag keep the
quiet-period rule. Test: `SourceSetupImplTest.queuedGuideWorkIsWaitedForEvenWhenItIsNotYetVisible`.
